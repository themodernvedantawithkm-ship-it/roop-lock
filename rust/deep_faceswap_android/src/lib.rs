//! JNI bridge between the Android app (`com.deepfaceswap.android.NativeBridge`)
//! and the Rust `deep_faceswap_core` engine.
//!
//! Every entry point returns a JSON string so the Kotlin side has a single,
//! stable way to read results and errors:
//!
//! ```json
//! { "ok": true }
//! { "ok": false, "error": "No faces detected in image" }
//! ```

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jstring};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex};

use deep_faceswap_core::swap::{SwapOptions, SwapSession, SwapStage, MAX_PIXEL_BOOST};
use deep_faceswap_core::tools::{ToolSession, MAX_INPUT_EDGE, TOOLS};
use ndarray::Array3;

/// Latest progress stage, polled from Kotlin while the swap runs on a worker
/// thread. `-1` means "no swap in flight".
static STAGE: AtomicI32 = AtomicI32::new(-1);
/// Faces finished in the current swap.
static FACES_DONE: AtomicI32 = AtomicI32::new(0);
/// Faces the current swap will process.
static FACES_TOTAL: AtomicI32 = AtomicI32::new(0);

/// Long-lived swap session used by the video pipeline, so the models and the
/// source faces are loaded once rather than once per frame.
/// One session per clone of the model.
///
/// Each sits behind its own lock, so clones never contend with each other. They
/// are clones of the *same* model on purpose: different models render faces
/// differently, which would make the face change from frame to frame.
static SESSIONS: Mutex<Vec<Arc<Mutex<SwapSession>>>> = Mutex::new(Vec::new());

/// How many clones to load when a session is opened. Set by the app first.
static WORKER_COUNT: AtomicI32 = AtomicI32::new(1);

/// Each clone costs a full copy of the models, so this is deliberately small.
const MAX_CLONES: i32 = 8;

/// Run `f` against one clone. The outer lock is released before the swap so two
/// workers can be inside the engine at the same time.
fn with_session<T>(
    worker: usize,
    f: impl FnOnce(&mut SwapSession) -> Result<T, String>,
) -> Result<T, String> {
    let session = {
        let guard = SESSIONS.lock().map_err(|_| "session lock poisoned".to_string())?;
        guard
            .get(worker)
            .ok_or_else(|| format!("no clone {worker} is open"))?
            .clone()
    };
    let mut held = session.lock().map_err(|_| "clone lock poisoned".to_string())?;
    f(&mut held)
}

/// Colour matching for subsequent swaps.
///
/// A process-wide switch rather than a new argument on every entry point: it is
/// set once per job by the app, and this keeps five JNI signatures untouched.
static COLOR_MATCH: AtomicBool = AtomicBool::new(true);
/// Face Blend, stored as f32 bits because atomics have no f32. 1.0 = full swap.
static FACE_BLEND: std::sync::atomic::AtomicU32 =
    std::sync::atomic::AtomicU32::new(0x3F80_0000); // 1.0f32

/// Copy a Java string into a Rust `String` (empty string on failure).
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_default()
}

/// Allocate a new Java string from a Rust `String`.
fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Run a face swap and return `{"ok":true}` or `{"ok":false,"error":"..."}`.
///
/// * `source_paths` – comma-separated paths to source face images
/// * `target_path` – path to the target image
/// * `output_path` – path to write the result to
/// * `detector` / `recognizer` / `swapper` – ONNX model paths
/// * `enhancer` / `landmark` – optional model paths (pass an empty string to disable)
/// * `use_mouth_mask` / `use_multi_face` – feature toggles
/// * `pixel_boost` – 1..=8, i.e. 128 / 256 / 384 / 512 / 768 / 1024px swap resolution
/// * `enhancer_strength` – 0.0..=1.0 blend of the restored face
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_swapImage(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    output_path: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    use_mouth_mask: jboolean,
    use_multi_face: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let output = jstring_to_string(&mut env, &output_path);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);

    let enhancer_opt = if enhancer.is_empty() {
        None
    } else {
        Some(enhancer.as_str())
    };
    let landmark_opt = if landmark.is_empty() {
        None
    } else {
        Some(landmark.as_str())
    };

    let mouth_mask = use_mouth_mask != 0;
    let multi_face = use_multi_face != 0;

    // The app's pipeline settings: how hard to work on resolution and how much
    // of the restored face to keep.
    let opts = SwapOptions {
        use_mouth_mask: mouth_mask,
        use_multi_face: multi_face,
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
        color_match: COLOR_MATCH.load(Ordering::Relaxed),
        face_blend: f32::from_bits(FACE_BLEND.load(Ordering::Relaxed)),
    };

    // Reset progress so a stale value from a previous run can never be shown.
    STAGE.store(SwapStage::LoadingModels as i32, Ordering::SeqCst);
    FACES_DONE.store(0, Ordering::SeqCst);
    FACES_TOTAL.store(0, Ordering::SeqCst);

    let mut on_stage = |stage: SwapStage, done: usize, total: usize| {
        STAGE.store(stage as i32, Ordering::SeqCst);
        FACES_DONE.store(done as i32, Ordering::SeqCst);
        FACES_TOTAL.store(total as i32, Ordering::SeqCst);
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::swap_faces_reporting(
            &source,
            &target,
            &output,
            &detector,
            &recognizer,
            &swapper,
            enhancer_opt,
            landmark_opt,
            opts,
            &mut on_stage,
        )
    }));

    let payload = match result {
        Ok(Ok(())) => serde_json::json!({ "ok": true }).to_string(),
        Ok(Err(e)) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        }
        Err(_) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": "native panic during face swap" })
                .to_string()
        }
    };

    to_jstring(&mut env, payload)
}

/// Returns the crate version so the app can display / log it.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_coreVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&mut env, env!("CARGO_PKG_VERSION").to_string())
}

/// Current progress stage, or `-1` when no swap is running.
///
/// Values match `deep_faceswap_core::swap::SwapStage`:
/// `0` models, `1` source, `2` detect, `3` swapping, `4` saving, `5` done.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_progress(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    STAGE.load(Ordering::SeqCst)
}

/// Face progress packed as `(done << 16) | total` for the current swap.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_faceProgress(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let done = FACES_DONE.load(Ordering::SeqCst).clamp(0, 0xFFFF);
    let total = FACES_TOTAL.load(Ordering::SeqCst).clamp(0, 0xFFFF);
    (done << 16) | total
}

/// Detect faces for the manual mapping UI.
///
/// Crops are written under `crops_dir` and their paths returned, so the app can
/// show the faces and let the user pair them up.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_nativeDetectFaces(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    detector: JString,
    crops_dir: JString,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let detector = jstring_to_string(&mut env, &detector);
    let crops_dir = jstring_to_string(&mut env, &crops_dir);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::detect_faces_for_mapping(&source, &target, &detector, &crops_dir)
    }));

    let payload = match result {
        Ok(Ok(faces)) => serde_json::json!({
            "ok": true,
            "source": faces.source.iter().map(face_to_json).collect::<Vec<_>>(),
            "target": faces.target.iter().map(face_to_json).collect::<Vec<_>>(),
        })
        .to_string(),
        Ok(Err(e)) => serde_json::json!({ "ok": false, "error": e.to_string() }).to_string(),
        Err(_) => serde_json::json!({
            "ok": false,
            "error": "native panic during face detection"
        })
        .to_string(),
    };

    to_jstring(&mut env, payload)
}

fn face_to_json(face: &deep_faceswap_core::swap::MappingFace) -> serde_json::Value {
    serde_json::json!({
        "index": face.index,
        "score": face.score,
        "bbox": face.bbox,
        "crop": face.crop_path,
    })
}

/// Swap with explicit source->target pairs, e.g. `"0:0,0:1"`.
///
/// Indices are positions in detection order (highest score first), matching what
/// [`Java_com_deepfaceswap_android_NativeBridge_detectFaces`] returned.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_swapImageMapped(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    output_path: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let output = jstring_to_string(&mut env, &output_path);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);
    let mapping_spec = jstring_to_string(&mut env, &mappings);

    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let enhancer_opt = if enhancer.is_empty() { None } else { Some(enhancer.as_str()) };
    let landmark_opt = if landmark.is_empty() { None } else { Some(landmark.as_str()) };

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: true,
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
        color_match: COLOR_MATCH.load(Ordering::Relaxed),
        face_blend: f32::from_bits(FACE_BLEND.load(Ordering::Relaxed)),
    };

    STAGE.store(SwapStage::LoadingModels as i32, Ordering::SeqCst);
    FACES_DONE.store(0, Ordering::SeqCst);
    FACES_TOTAL.store(pairs.len() as i32, Ordering::SeqCst);

    let mut on_stage = |stage: SwapStage, done: usize, total: usize| {
        STAGE.store(stage as i32, Ordering::SeqCst);
        FACES_DONE.store(done as i32, Ordering::SeqCst);
        FACES_TOTAL.store(total as i32, Ordering::SeqCst);
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::swap_faces_mapped(
            &source,
            &target,
            &output,
            &detector,
            &recognizer,
            &swapper,
            enhancer_opt,
            landmark_opt,
            opts,
            &pairs,
            &mut on_stage,
        )
    }));

    let payload = match result {
        Ok(Ok(())) => serde_json::json!({ "ok": true }).to_string(),
        Ok(Err(e)) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        }
        Err(_) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": "native panic during face swap" })
                .to_string()
        }
    };

    to_jstring(&mut env, payload)
}

/// Swap faces across every frame of an animated GIF or WebP.
///
/// `mappings` is the same `"s:t,s:t"` spec as `swapImageMapped`; pass an empty
/// string to map automatically. The result is always written as a GIF.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_swapAnimation(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    output_path: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let output = jstring_to_string(&mut env, &output_path);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);
    let mapping_spec = jstring_to_string(&mut env, &mappings);

    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let enhancer_opt = if enhancer.is_empty() { None } else { Some(enhancer.as_str()) };
    let landmark_opt = if landmark.is_empty() { None } else { Some(landmark.as_str()) };

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: pairs.is_empty(),
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
        color_match: COLOR_MATCH.load(Ordering::Relaxed),
        face_blend: f32::from_bits(FACE_BLEND.load(Ordering::Relaxed)),
    };

    STAGE.store(SwapStage::LoadingModels as i32, Ordering::SeqCst);
    FACES_DONE.store(0, Ordering::SeqCst);
    FACES_TOTAL.store(0, Ordering::SeqCst);

    let mut on_stage = |stage: SwapStage, done: usize, total: usize| {
        STAGE.store(stage as i32, Ordering::SeqCst);
        FACES_DONE.store(done as i32, Ordering::SeqCst);
        FACES_TOTAL.store(total as i32, Ordering::SeqCst);
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::swap_animation(
            &source,
            &target,
            &output,
            &detector,
            &recognizer,
            &swapper,
            enhancer_opt,
            landmark_opt,
            opts,
            &pairs,
            &mut on_stage,
        )
    }));

    let payload = match result {
        Ok(Ok(frames)) => serde_json::json!({ "ok": true, "frames": frames }).to_string(),
        Ok(Err(e)) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        }
        Err(_) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": "native panic during animation swap" })
                .to_string()
        }
    };

    to_jstring(&mut env, payload)
}

/// Open a reusable swap session for a frame sequence.
///
/// Loads the models and the source face(s) once; every later `sessionSwapFrame`
/// reuses them. Replaces any previously open session.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_sessionOpen(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    use_mouth_mask: jboolean,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);
    let mouth_mask = use_mouth_mask != 0;

    let enhancer_opt = if enhancer.is_empty() { None } else { Some(enhancer.as_str()) };
    let landmark_opt = if landmark.is_empty() { None } else { Some(landmark.as_str()) };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let count = WORKER_COUNT.load(Ordering::Relaxed).clamp(1, MAX_CLONES) as usize;
        let mut built: Vec<Arc<Mutex<SwapSession>>> = Vec::with_capacity(count);
        for _ in 0..count {
            let session = SwapSession::new(
                &source,
                &detector,
                &recognizer,
                &swapper,
                enhancer_opt,
                landmark_opt,
                mouth_mask,
            )
            .map_err(|e| e.to_string())?;
            built.push(Arc::new(Mutex::new(session)));
        }
        let faces = match built.first() {
            Some(first) => first.lock().map(|s| s.source_face_count()).unwrap_or(0),
            None => 0,
        };
        Ok::<_, String>((built, faces))
    }));

    let payload = match result {
        Ok(Ok((built, faces))) => {
            match SESSIONS.lock() {
                Ok(mut guard) => {
                    *guard = built;
                    serde_json::json!({
                        "ok": true,
                        "source_faces": faces,
                        "clones": guard.len(),
                    })
                    .to_string()
                }
                Err(_) => serde_json::json!({
                    "ok": false,
                    "error": "session lock poisoned"
                })
                .to_string(),
            }
        }
        Ok(Err(e)) => serde_json::json!({ "ok": false, "error": e.to_string() }).to_string(),
        Err(_) => serde_json::json!({
            "ok": false,
            "error": "native panic while opening the swap session"
        })
        .to_string(),
    };

    to_jstring(&mut env, payload)
}

/// Swap one frame using the open session.
///
/// `mappings` uses the same `"s:t,s:t"` spec; an empty string maps automatically.
/// Returns the number of faces swapped in this frame (0 means the frame was
/// passed through unchanged because no face was found).
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_sessionSwapFrame(
    mut env: JNIEnv,
    _class: JClass,
    frame_in: JString,
    frame_out: JString,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let frame_in = jstring_to_string(&mut env, &frame_in);
    let frame_out = jstring_to_string(&mut env, &frame_out);
    let mapping_spec = jstring_to_string(&mut env, &mappings);

    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: pairs.is_empty(),
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
        color_match: COLOR_MATCH.load(Ordering::Relaxed),
        face_blend: f32::from_bits(FACE_BLEND.load(Ordering::Relaxed)),
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_session(0, |session| {
            session
                .swap_frame(&frame_in, &frame_out, opts, &pairs)
                .map_err(|e| e.to_string())
        })
    }));

    let payload = match result {
        Ok(Ok(faces)) => serde_json::json!({ "ok": true, "faces": faces }).to_string(),
        Ok(Err(e)) => serde_json::json!({ "ok": false, "error": e }).to_string(),
        Err(_) => serde_json::json!({
            "ok": false,
            "error": "native panic while swapping a frame"
        })
        .to_string(),
    };

    to_jstring(&mut env, payload)
}

/// Swap one frame handed over as raw RGBA bytes, returning RGBA bytes.
///
/// The video path uses this instead of `sessionSwapFrame`: it removes four PNG
/// operations per frame (encode and decode on each side of the call).
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_sessionSwapFrameRaw(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    frame: JByteArray,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jbyteArray {
    let mapping_spec = jstring_to_string(&mut env, &mappings);
    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: pairs.is_empty(),
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
        color_match: COLOR_MATCH.load(Ordering::Relaxed),
        face_blend: f32::from_bits(FACE_BLEND.load(Ordering::Relaxed)),
    };

    let input = match env.convert_byte_array(&frame) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    if w == 0 || h == 0 || input.len() < w * h * 4 {
        return std::ptr::null_mut();
    }

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_session(0, |session| {
            session
                .swap_frame_rgba(w, h, &input, opts, &pairs)
                .map_err(|e| e.to_string())
        })
    }));

    // A null return means "keep the original frame" on the Kotlin side.
    match result {
        Ok(Ok((bytes, _faces))) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// Swap a frame on a named clone.
///
/// The app runs one thread per clone, so this is what actually gives the
/// parallelism: the clones are independent, each with its own copy of the model.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_sessionSwapFrameRawWorker(
    mut env: JNIEnv,
    _class: JClass,
    worker: jint,
    width: jint,
    height: jint,
    frame: JByteArray,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jbyteArray {
    let mapping_spec = jstring_to_string(&mut env, &mappings);
    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: pairs.is_empty(),
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
        color_match: COLOR_MATCH.load(Ordering::Relaxed),
        face_blend: f32::from_bits(FACE_BLEND.load(Ordering::Relaxed)),
    };

    let input = match env.convert_byte_array(&frame) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    if w == 0 || h == 0 || input.len() < w * h * 4 {
        return std::ptr::null_mut();
    }

    let index = worker.max(0) as usize;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_session(index, |session| {
            session
                .swap_frame_rgba(w, h, &input, opts, &pairs)
                .map_err(|e| e.to_string())
        })
    }));

    match result {
        Ok(Ok((bytes, _faces))) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// The loaded tool model, kept between calls so a batch does not reload it.
static TOOL_SESSION: Mutex<Option<ToolSession>> = Mutex::new(None);

/// The tool registry, as JSON, so the app keeps no second copy of it.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_listToolsNative(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = serde_json::json!({
        "ok": true,
        "tools": TOOLS,
    })
    .to_string();
    to_jstring(&mut env, payload)
}

/// Run a tool over an RGBA image and hand back RGBA.
///
/// Returns null on failure; the reason is not carried on this path, so the
/// caller should treat null as "it did not work" and re-run through the
/// reporting path if it needs the detail.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_runToolNative(
    mut env: JNIEnv,
    _class: JClass,
    tool_id: JString,
    model_path: JString,
    width: jint,
    height: jint,
    rgba: JByteArray,
) -> jbyteArray {
    let id = jstring_to_string(&mut env, &tool_id);
    let path = jstring_to_string(&mut env, &model_path);
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;

    let input = match env.convert_byte_array(&rgba) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };
    if w == 0 || h == 0 || input.len() < w * h * 4 {
        return std::ptr::null_mut();
    }

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // A 4x pass over a large photo is not a question of patience: the result
        // would not fit in memory at all. Refuse with the numbers rather than
        // being killed part-way through.
        if w > MAX_INPUT_EDGE || h > MAX_INPUT_EDGE {
            return Err(format!(
                "This image is {w} by {h}. Upscaling runs up to {MAX_INPUT_EDGE} pixels on a side, because 4x of anything larger will not fit in memory. Crop it first."
            ));
        }

        let mut image = Array3::<u8>::zeros((h, w, 3));
        for y in 0..h {
            for x in 0..w {
                let i = (y * w + x) * 4;
                image[[y, x, 0]] = input[i];
                image[[y, x, 1]] = input[i + 1];
                image[[y, x, 2]] = input[i + 2];
            }
        }

        let mut guard = TOOL_SESSION
            .lock()
            .map_err(|_| "tool lock poisoned".to_string())?;
        let stale = match guard.as_ref() {
            Some(held) => held.tool_id() != id.as_str(),
            None => true,
        };
        if stale {
            let loaded =
                ToolSession::load(&id, &path).map_err(|e| format!("{e}"))?;
            *guard = Some(loaded);
        }
        let held = guard
            .as_mut()
            .ok_or_else(|| "the tool failed to load".to_string())?;
        let out = held.run(&image).map_err(|e| format!("{e}"))?;

        let (out_h, out_w, _) = out.dim();
        let mut bytes = Vec::with_capacity(out_h * out_w * 4);
        for y in 0..out_h {
            for x in 0..out_w {
                bytes.push(out[[y, x, 0]]);
                bytes.push(out[[y, x, 1]]);
                bytes.push(out[[y, x, 2]]);
                bytes.push(255);
            }
        }
        Ok::<Vec<u8>, String>(bytes)
    }));

    match result {
        Ok(Ok(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// How many clones to load on the next `sessionOpen`.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_setCloneCountNative(
    _env: JNIEnv,
    _class: JClass,
    count: jint,
) {
    let clones = count.clamp(1, MAX_CLONES);
    WORKER_COUNT.store(clones, Ordering::Relaxed);

    // Split the cores between the clones. ONNX Runtime defaults every session to
    // "use all cores", so N clones left alone spawn N pools over the same cores
    // and spend the time contending - which can be slower than a single clone.
    // One clone keeps the default (0 = let ORT decide).
    let cores = std::thread::available_parallelism()
        .map(|n| n.get() as i32)
        .unwrap_or(4);
    let per_clone = if clones <= 1 { 0 } else { (cores / clones).max(1) };
    deep_faceswap_core::config::set_intra_threads(per_clone);
}

/// Turn colour matching on or off for subsequent swaps.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_setColorMatchNative(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    COLOR_MATCH.store(enabled != 0, Ordering::Relaxed);
}

pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_setFaceBlendNative(
    _env: JNIEnv,
    _class: JClass,
    blend: jfloat,
) {
    FACE_BLEND.store(blend.to_bits(), Ordering::Relaxed);
}

/// Release the swap session and free its models.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_sessionClose(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match SESSIONS.lock() {
        Ok(mut guard) => {
            guard.clear();
            serde_json::json!({ "ok": true }).to_string()
        }
        Err(_) => serde_json::json!({ "ok": false, "error": "session lock poisoned" }).to_string(),
    };
    to_jstring(&mut env, payload)
}

/// Cosine similarity between the face in `source_path` and the face in
/// `result_path`. Returns -1.0 when it cannot be computed.
///
/// Why this exists: a swap can run, write a file, and change almost nothing -
/// which reads as "the swap did not happen" with no way to tell whether the
/// identity moved or the pixels merely shifted. One number answers that.
///
/// It is deliberately **read-only and fail-safe**. It runs after the job has
/// finished, opens its own detectors, and returns -1.0 on any problem, so a
/// fault here can never affect a swap that already succeeded.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_identityScoreNative(
    mut env: JNIEnv,
    _class: JClass,
    source_path: JString,
    result_path: JString,
    detector: JString,
    recognizer: JString,
) -> jfloat {
    let source = jstring_to_string(&mut env, &source_path);
    let result = jstring_to_string(&mut env, &result_path);
    let det_path = jstring_to_string(&mut env, &detector);
    let rec_path = jstring_to_string(&mut env, &recognizer);

    let computed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Option<f32> {
        use deep_faceswap_core::alignment::align_face;
        use deep_faceswap_core::detection::FaceDetector;
        use deep_faceswap_core::recognition::FaceRecognizer;
        use deep_faceswap_core::utils::image::{load_image, to_rgb8};

        fn embed(
            path: &str,
            det_path: &str,
            rec_path: &str,
        ) -> Option<Vec<f32>> {
            use deep_faceswap_core::alignment::align_face;
            use deep_faceswap_core::detection::FaceDetector;
            use deep_faceswap_core::recognition::FaceRecognizer;
            use deep_faceswap_core::utils::image::{load_image, to_rgb8};

            let img = load_image(path).ok()?;
            let rgb = to_rgb8(&img);
            let (w, h) = (rgb.width() as usize, rgb.height() as usize);
            if w == 0 || h == 0 {
                return None;
            }
            let mut arr = Array3::<u8>::zeros((h, w, 3));
            for y in 0..h {
                for x in 0..w {
                    let px = rgb.get_pixel(x as u32, y as u32);
                    arr[[y, x, 0]] = px[0];
                    arr[[y, x, 1]] = px[1];
                    arr[[y, x, 2]] = px[2];
                }
            }
            let mut det = FaceDetector::new(det_path).ok()?;
            let faces = det.detect(&arr, 0.5, 0.4).ok()?;
            // Largest by area, not highest score: on a swapped result the
            // subject is usually the biggest face, and a small bystander can
            // easily outscore it.
            let face = faces.into_iter().max_by(|a, b| {
                let aa = (a.bbox.x2 - a.bbox.x1) * (a.bbox.y2 - a.bbox.y1);
                let bb = (b.bbox.x2 - b.bbox.x1) * (b.bbox.y2 - b.bbox.y1);
                aa.partial_cmp(&bb).unwrap_or(std::cmp::Ordering::Equal)
            })?;
            let aligned = align_face(&arr, &face, 112).ok()?;
            let mut rec = FaceRecognizer::new(rec_path).ok()?;
            rec.extract_embedding(&aligned.aligned_image).ok()
        }

        let a = embed(&source, &det_path, &rec_path)?;
        let b = embed(&result, &det_path, &rec_path)?;
        if a.len() != b.len() || a.is_empty() {
            return None;
        }
        let dot: f32 = a.iter().zip(b.iter()).map(|(x, y)| x * y).sum();
        let na: f32 = a.iter().map(|x| x * x).sum::<f32>().sqrt();
        let nb: f32 = b.iter().map(|x| x * x).sum::<f32>().sqrt();
        if na <= 0.0 || nb <= 0.0 {
            return None;
        }
        Some(dot / (na * nb))
    }))
    .ok()
    .flatten();

    computed.unwrap_or(-1.0)
}
