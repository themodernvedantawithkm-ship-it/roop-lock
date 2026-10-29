//! Main face swap operation

use crate::alignment;
use crate::detection::FaceDetector;
use crate::enhancer::FaceEnhancer;
use crate::landmark::LandmarkDetector;
use crate::mouth_mask;
use crate::multi_face;
use crate::recognition::FaceRecognizer;
use crate::swapper::FaceSwapper;
use crate::types::{DetectedFace, FaceSwapError, Result, SourceFaceInfo};
#[cfg(feature = "video")]
use crate::types::ClusterMapping;
use crate::utils::color;
use crate::utils::image as img_io;
use crate::utils::rgb;
use crate::verbose::{
    EVENT_ALIGN_FACE, EVENT_COMPLETE, EVENT_FACE_DETECTED, EVENT_LOAD_MODEL, EVENT_PASTE_BACK,
};
#[cfg(feature = "video")]
use crate::video::{
    build_cluster_info, build_face_lookup, cluster_faces, extract_frames, scan_frames_for_faces,
};
use crate::{log_additional, log_main};
use ndarray::Array3;
use std::collections::HashMap;
use std::fs::create_dir_all;
use std::time::Instant;

/// Coarse progress stages reported while a swap runs.
///
/// These exist so a host (the Android app) can show honest progress: a still
/// image is a single pass, so there is no percentage to report — only which
/// stage is currently running, and how many faces have been done.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum SwapStage {
    /// Loading the detector / recogniser / swapper models.
    LoadingModels = 0,
    /// Loading and scanning the source image(s).
    LoadingSource = 1,
    /// Loading the target and detecting faces in it.
    DetectingFaces = 2,
    /// Swapping faces (and enhancing, per face).
    Swapping = 3,
    /// Writing the result.
    Saving = 4,
    /// Finished.
    Done = 5,
}

/// Highest supported pixel-boost grid. 8 x 128 = 1024px, i.e. 64 model passes.
pub const MAX_PIXEL_BOOST: u32 = 8;

/// Callback signature: `(stage, faces_done, faces_total)`.
pub type StageCallback<'a> = &'a mut dyn FnMut(SwapStage, usize, usize);

/// Everything that shapes a swap beyond the model paths themselves.
///
/// This is the "pipeline" the app builds: how hard to work on resolution
/// (pixel boost), whether to run a master restore model afterwards and how
/// strongly to apply it.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SwapOptions {
    /// Preserve the target's mouth expression using the 106-point landmarks.
    pub use_mouth_mask: bool,
    /// Map the source face onto every detected target face.
    pub use_multi_face: bool,
    /// Pixel boost grid: 1 = 128px, 2 = 256, 3 = 384, 4 = 512, 6 = 768, 8 = 1024.
    /// Clamped to 1..=8. Cost scales with the square of this value.
    pub pixel_boost: u32,
    /// How much of the restored face to keep: 0.0 untouched, 1.0 full restore.
    pub enhancer_strength: f32,
    /// Match the swapped face's colour to the target's own skin tone and light.
    pub color_match: bool,
    /// How much of the swapped face to keep, 0.0 = the untouched target face,
    /// 1.0 = the swap in full.
    ///
    /// This is the honest lever behind "Face Blend". Below 1.0 the swapped face
    /// is mixed back toward the original aligned target, so the result keeps some
    /// of the original's structure - useful when a strong swap reads as a mask.
    pub face_blend: f32,

    /// Replace the swapped face's shading with the target's.
    ///
    /// A swap copies pixels, and pixels carry the *source's* lighting. The
    /// target's lighting lives in the photo around it, so without this the face
    /// is lit from one direction and the scene from another - which reads as
    /// pasted even when the colour matches.
    pub relight: bool,

    /// Horizontal and vertical scale applied to the swapped face, 1.0 = none.
    ///
    /// This is the honest limit of what a scale can do: it changes the face's
    /// overall proportion - height against width, and the balance between
    /// horizontal and vertical distances - but it cannot move one feature
    /// relative to another, because scaling moves them all together. Local
    /// feature placement needs a non-linear warp.
    pub shape_sx: f32,
    pub shape_sy: f32,
}

impl Default for SwapOptions {
    fn default() -> Self {
        Self {
            use_mouth_mask: false,
            use_multi_face: false,
            pixel_boost: 1,
            enhancer_strength: 1.0,
            color_match: true,
            face_blend: 1.0,
            relight: false,
            shape_sx: 1.0,
            shape_sy: 1.0,
        }
    }
}

/// Swap a single source face into a single target face
///
/// # Arguments
/// * `target_image` - Mutable target image (HWC, RGB, u8)
/// * `target_face` - Detected face in target image
/// * `source_image` - Source image for face alignment (HWC, RGB, u8)
/// * `source_embedding` - Pre-extracted source face embedding (512D)
/// * `swapper` - Face swapper model
/// * `enhancer` - Optional face enhancer model
/// * `landmark_detector` - Optional 106-point landmark detector
/// * `use_mouth_mask` - Whether to apply mouth mask
///
/// # Returns
/// Modified target_image with swapped face and elapsed time in seconds
pub fn swap_single_pair(
    target_image: &mut Array3<u8>,
    target_face: &DetectedFace,
    source_image: &Array3<u8>,
    source_embedding: &[f32],
    swapper: &mut FaceSwapper,
    enhancer: &mut Option<FaceEnhancer>,
    landmark_detector: &mut Option<LandmarkDetector>,
    use_mouth_mask: bool,
) -> Result<f64> {
    swap_single_pair_opts(
        target_image,
        target_face,
        source_image,
        None,
        source_embedding,
        swapper,
        enhancer,
        landmark_detector,
        SwapOptions {
            use_mouth_mask,
            ..SwapOptions::default()
        },
    )
}

/// Swap a single source face into a single target face, honouring `opts`.
///
/// # Arguments
/// * `opts` - Pixel boost, enhancer strength and the mouth-mask toggle
///
/// # Returns
/// Modified target_image with swapped face and elapsed time in seconds
pub fn swap_single_pair_opts(
    target_image: &mut Array3<u8>,
    target_face: &DetectedFace,
    source_image: &Array3<u8>,
    source_face: Option<&DetectedFace>,
    source_embedding: &[f32],
    swapper: &mut FaceSwapper,
    enhancer: &mut Option<FaceEnhancer>,
    landmark_detector: &mut Option<LandmarkDetector>,
    opts: SwapOptions,
) -> Result<f64> {
    let start_time = Instant::now();
    let use_mouth_mask = opts.use_mouth_mask;

    // Detect 106 landmarks on target face before swap (needed for mouth mask)
    let mouth_mask_data = if use_mouth_mask {
        if let Some(ref mut lm_detector) = landmark_detector {
            log_additional!("mouth_mask", "Detecting 106 landmarks on target face");
            let landmarks = lm_detector.detect(target_image, target_face)?;
            let data = mouth_mask::create_mouth_mask(target_image, &landmarks)?;
            Some(data)
        } else {
            None
        }
    } else {
        None
    };

    // Pixel boost: a swapper always runs at its native size — 128 for inswapper,
    // 256 for HyperSwap. Aligning larger and running the model over an
    // interleaved NxN grid of native-size sub-images gives a sharper result at
    // N^2 passes (inswapper: 2 => 256 ... 8 => 1024; HyperSwap: 2 => 512 ... 4 => 1024).
    let max_grid = swapper.max_grid() as u32;
    let grid = opts.pixel_boost.clamp(1, max_grid) as usize;
    let swap_size = (swapper.model_size() * grid) as u32;

    // Families differ in the framing they were trained on, so the warp template
    // comes from the swapper rather than being fixed.
    let template = swapper.kind().target_template();

    // UniFace is fed an aligned source image instead of an embedding.
    let source_frame = if swapper.kind().needs_source_frame() {
        let face = source_face.ok_or_else(|| {
            FaceSwapError::InvalidInput(
                "This swapper needs the source face, which was not supplied".to_string(),
            )
        })?;
        let aligned = alignment::align_face_with_template(
            source_image,
            face,
            swapper.model_size() as u32,
            template,
        )?;
        Some(aligned.aligned_image)
    } else {
        None
    };

    log_additional!(EVENT_ALIGN_FACE, "Aligning target face");
    let target_aligned =
        alignment::align_face_with_template(target_image, target_face, swap_size, template)?;

    let swapped_face = if grid <= 1 {
        match source_frame.as_ref() {
            Some(frame) => swapper.swap_with_source_frame(&target_aligned.aligned_image, frame)?,
            None => swapper.swap(&target_aligned.aligned_image, source_embedding)?,
        }
    } else {
        swapper.swap_boosted_full(
            &target_aligned.aligned_image,
            source_embedding,
            source_frame.as_ref(),
            grid,
        )?
    };

    // Face Blend: pull the swapped face back toward the original target face.
    //
    // Applied here, before the first paste, so both the plain path and the
    // enhancer path inherit it - the enhancer re-aligns from the image that this
    // paste produced.
    let blend = opts.face_blend.clamp(0.0, 1.0);
    let swapped_face = if blend >= 0.999 {
        swapped_face
    } else {
        let mut mixed = swapped_face;
        let original = &target_aligned.aligned_image;
        let side = swap_size as usize;
        for c in 0..3usize {
            for y in 0..side {
                for x in 0..side {
                    let sw = mixed[[0, c, y, x]];
                    let og = original[[0, c, y, x]];
                    mixed[[0, c, y, x]] = og * (1.0 - blend) + sw * blend;
                }
            }
        }
        mixed
    };

    // Shape: rescale the swapped face so its overall proportion moves toward the
    // source's. Capped by the caller; the limits here are a second guard.
    let swapped_face = if (opts.shape_sx - 1.0).abs() > 0.002 || (opts.shape_sy - 1.0).abs() > 0.002 {
        log_additional!(EVENT_ALIGN_FACE, "Adjusting face proportion toward the source");
        rescale_face(swapped_face, opts.shape_sx, opts.shape_sy, swap_size)
    } else {
        swapped_face
    };

    // Physics: take the target's shading and give it to the swapped face.
    let swapped_face = if opts.relight {
        log_additional!(EVENT_PASTE_BACK, "Relighting the swapped face to the target");
        relight_to_target(&swapped_face, &target_aligned.aligned_image, swap_size)
    } else {
        swapped_face
    };

    // The swapper works in normalised f32, so the colour reference is the
    // original aligned target face converted the same way paste_back does it.
    let color_reference = if opts.color_match {
        log_additional!(EVENT_PASTE_BACK, "Matching the swapped face's colour");
        Some(Array3::<u8>::from_shape_fn(
            (swap_size as usize, swap_size as usize, 3),
            |(y, x, c)| {
                (target_aligned.aligned_image[[0, c, y, x]] * 255.0).clamp(0.0, 255.0) as u8
            },
        ))
    } else {
        None
    };

    log_additional!(EVENT_PASTE_BACK, "Pasting swapped face back");
    alignment::paste_back_inplace(
        target_image,
        &swapped_face,
        &target_aligned.transform,
        swap_size,
        color_reference.as_ref(),
    )?;

    // Apply mouth mask after swap but before enhancement
    if let Some(ref data) = mouth_mask_data {
        log_additional!("mouth_mask", "Applying mouth mask");
        mouth_mask::apply_mouth_mask(target_image, data);
    }

    // Enhance face if enhancer is provided
    if let Some(ref mut enh) = enhancer {
        log_additional!("enhance_face", "Enhancing face at original resolution");

        // Align target face from current image to 512x512 for enhancement
        let target_aligned_512 = alignment::align_face(target_image, target_face, 512)?;
        let before = target_aligned_512.aligned_image.clone();

        // The enhanced face gets the same treatment, matched against the face as
        // it stands now, so restoring detail cannot shift the colour again.
        let enhanced_reference = if opts.color_match {
            Some(Array3::<u8>::from_shape_fn((512, 512, 3), |(y, x, c)| {
                (before[[0, c, y, x]] * 255.0).clamp(0.0, 255.0) as u8
            }))
        } else {
            None
        };

        // Enhance the 512x512 aligned face
        let enhanced_512 = enh.enhance(&before)?;

        // Blend restored against un-restored by strength: 0.0 leaves the swap
        // untouched, 1.0 takes the restoration in full.
        let strength = opts.enhancer_strength.clamp(0.0, 1.0);
        let blended = if strength >= 0.999 {
            enhanced_512
        } else {
            let mut mixed = before;
            for c in 0..3 {
                for y in 0..512usize {
                    for x in 0..512usize {
                        let raw = mixed[[0, c, y, x]];
                        let restored = enhanced_512[[0, c, y, x]];
                        mixed[[0, c, y, x]] = raw * (1.0 - strength) + restored * strength;
                    }
                }
            }
            mixed
        };

        // Paste enhanced face back in-place
        alignment::paste_back_inplace(
            target_image,
            &blended,
            &target_aligned_512.transform,
            512,
            enhanced_reference.as_ref(),
        )?;
    }

    Ok(start_time.elapsed().as_secs_f64())
}

/// Swap multiple faces using explicit mappings (no interactive prompts)
///
/// This is the programmatic API suitable external usage (e.g. for REST endpoints).
/// Extracts embeddings for all source faces, then applies each mapping.
///
/// # Arguments
/// * `source_face_infos` - Source faces with origin metadata
/// * `target_faces` - All detected faces in target image
/// * `source_images` - All loaded source images
/// * `target_image` - Mutable target image (HWC, RGB, u8)
/// * `mappings` - Explicit source->target face mappings
/// * `recognizer` - Face recognizer for embedding extraction
/// * `swapper` - Face swapper model
/// * `enhancer` - Optional face enhancer model
/// * `landmark_detector` - Optional 106-point landmark detector
/// * `use_mouth_mask` - Whether to apply mouth mask
///
/// # Returns
/// Elapsed time in seconds
pub fn swap_with_mappings(
    source_face_infos: &[SourceFaceInfo],
    target_faces: &[DetectedFace],
    source_images: &[Array3<u8>],
    target_image: &mut Array3<u8>,
    mappings: &[crate::types::FaceMapping],
    recognizer: &mut FaceRecognizer,
    swapper: &mut FaceSwapper,
    enhancer: &mut Option<FaceEnhancer>,
    landmark_detector: &mut Option<LandmarkDetector>,
    opts: SwapOptions,
    on_stage: StageCallback,
) -> Result<f64> {
    log_main!(
        "multi_face",
        "Processing face mappings",
        count = mappings.len()
    );

    let total_faces = mappings.len();
    on_stage(SwapStage::Swapping, 0, total_faces);

    let start_time = Instant::now();

    // Extract embeddings for all source faces upfront
    let mut source_embeddings = Vec::new();
    for info in source_face_infos.iter() {
        log_additional!(
            EVENT_ALIGN_FACE,
            "Aligning source face",
            filename = &info.source_filename
        );
        let source_img = &source_images[info.source_image_index];
        let source_aligned = alignment::align_face(source_img, &info.face, 112)?;
        let embedding = recognizer.extract_embedding(&source_aligned.aligned_image)?;
        source_embeddings.push(embedding);
    }

    // Swap each mapped face
    for (face_index, mapping) in mappings.iter().enumerate() {
        log_additional!(
            "multi_face",
            "Swapping face",
            source_idx = mapping.source_idx,
            target_idx = mapping.target_idx
        );

        let source_info = &source_face_infos[mapping.source_idx];
        let source_img = &source_images[source_info.source_image_index];
        let source_embedding = &source_embeddings[mapping.source_idx];
        let target_face = &target_faces[mapping.target_idx];

        let _ = swap_single_pair_opts(
            target_image,
            target_face,
            source_img,
            Some(&source_info.face),
            source_embedding,
            swapper,
            enhancer,
            landmark_detector,
            opts,
        )?;

        on_stage(SwapStage::Swapping, face_index + 1, total_faces);
    }

    Ok(start_time.elapsed().as_secs_f64())
}

/// Images and faces loaded for a swap.
struct LoadedImages {
    source_images: Vec<Array3<u8>>,
    all_source_faces: Vec<SourceFaceInfo>,
    target_array: Array3<u8>,
    target_faces: Vec<DetectedFace>,
}

/// Load the source image(s) and the target, detecting faces in both.
///
/// Shared by the automatic and the explicitly-mapped swap paths.
fn load_and_detect(
    detector: &mut FaceDetector,
    source_path: &str,
    target_path: &str,
    on_stage: StageCallback,
) -> Result<LoadedImages> {
    on_stage(SwapStage::LoadingSource, 0, 0);

    let action_start = Instant::now();
    let source_paths: Vec<&str> = source_path.split(',').map(|s| s.trim()).collect();
    let mut source_images: Vec<Array3<u8>> = Vec::new();
    let mut all_source_faces: Vec<SourceFaceInfo> = Vec::new();

    for (img_idx, path) in source_paths.iter().enumerate() {
        log_additional!("load_image", "Loading source image", path = path);

        let img = img_io::load_image(path)?;
        let rgb = img_io::to_rgb8(&img);
        let array = rgb::rgb_to_array3(&rgb);

        let detect_start = Instant::now();
        let faces = detector.detect(&array, 0.5, 0.4)?;

        if faces.is_empty() {
            log_main!("warn", "No faces detected in source image", path = path);
        } else {
            log_main!(
                EVENT_FACE_DETECTED,
                "Detected faces in source image",
                path = path,
                count = faces.len(),
                elapsed_s = detect_start.elapsed().as_secs_f64()
            );

            let filename = std::path::Path::new(path)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or(path);

            for face in faces {
                all_source_faces.push(SourceFaceInfo {
                    face,
                    source_image_index: img_idx,
                    source_filename: filename.to_string(),
                });
            }
        }

        source_images.push(array);
    }

    if all_source_faces.is_empty() {
        return Err(FaceSwapError::NoFacesDetected);
    }

    log_main!(
        "load_source",
        "Loading source images complete",
        count = all_source_faces.len(),
        elapsed_s = action_start.elapsed().as_secs_f64()
    );

    on_stage(SwapStage::DetectingFaces, 0, 0);

    let action_start = Instant::now();
    let target_img = img_io::load_image(target_path)?;
    let target_rgb = img_io::to_rgb8(&target_img);
    let target_array = rgb::rgb_to_array3(&target_rgb);

    let target_faces = detector.detect(&target_array, 0.5, 0.4)?;
    if target_faces.is_empty() {
        return Err(FaceSwapError::NoFacesDetected);
    }
    log_main!(
        "load_target",
        "Loading target image complete",
        count = target_faces.len(),
        elapsed_s = action_start.elapsed().as_secs_f64()
    );

    Ok(LoadedImages {
        source_images,
        all_source_faces,
        target_array,
        target_faces,
    })
}

/// Swap a face from a source image into a target image.
///
/// This is the image entry point the Android app uses. Multi-face mode maps the
/// highest-scoring source face onto **every** detected target face. It never
/// prompts: an interactive mapping would need a terminal, and a mobile app has
/// none. Use [`swap_faces_reporting`] to observe progress.
#[allow(clippy::too_many_arguments)]
pub fn swap_faces(
    source_path: &str,
    target_path: &str,
    output_path: &str,
    detector_model: &str,
    recognizer_model: &str,
    swapper_model: &str,
    enhancer_model: Option<&str>,
    landmark_model: Option<&str>,
    use_mouth_mask: bool,
    use_multi_face: bool,
) -> Result<()> {
    swap_faces_reporting(
        source_path,
        target_path,
        output_path,
        detector_model,
        recognizer_model,
        swapper_model,
        enhancer_model,
        landmark_model,
        SwapOptions {
            use_mouth_mask,
            use_multi_face,
            ..SwapOptions::default()
        },
        &mut |_, _, _| {},
    )
}

/// Simple face swap between two images
///
/// # Arguments
/// * `source_path` - Path to source image (face to extract)
/// * `target_path` - Path to target image (face to replace)
/// * `output_path` - Path to save result
/// * `detector_model` - Path to detection model (det_10g.onnx)
/// * `recognizer_model` - Path to recognition model (w600k_r50.onnx)
/// * `swapper_model` - Path to swapper model (inswapper_128.onnx)
/// * `enhancer_model` - Optional path to enhancement model (GFPGAN of version
/// 1.4 is tested by me, but others may work too. If you are figured it out let me know)
/// * `landmark_model` - Optional path to 106-point landmark model (2d106det.onnx).
/// Required when `mouth_mask` is true.
/// * `mouth_mask` - Whether to apply mouth mask to preserve target's mouth expression
/// * `multi_face` - Swap every detected target face instead of only the best one
///
/// # Returns
/// `Ok(())` on success, error otherwise
pub fn swap_faces_reporting(
    source_path: &str,
    target_path: &str,
    output_path: &str,
    detector_model: &str,
    recognizer_model: &str,
    swapper_model: &str,
    enhancer_model: Option<&str>,
    landmark_model: Option<&str>,
    opts: SwapOptions,
    on_stage: StageCallback,
) -> Result<()> {
    log_main!(
        "swap_init",
        "Initializing face swap",
        source = source_path,
        target = target_path,
        output = output_path,
        multi_face = opts.use_multi_face
    );

    on_stage(SwapStage::LoadingModels, 0, 0);
    let mut detector = FaceDetector::new(detector_model)?;
    let mut recognizer = FaceRecognizer::new(recognizer_model)?;
    let mut swapper = FaceSwapper::new(swapper_model)?;
    // A restore model that will not load must not take the swap with it. This
    // used to be `.transpose()?`, so a corrupt or unsupported enhancer aborted
    // the whole job before a single face was touched - the app reported "swap
    // stopped" while the swap itself was fine. The enhancer is optional by
    // definition: without it the output is simply not restored.
    let mut enhancer = enhancer_model.and_then(|path| match FaceEnhancer::new(path) {
        Ok(e) => Some(e),
        Err(err) => {
            log_additional!(
                "load_models",
                "Restore model could not be loaded, continuing without it: {err}"
            );
            None
        }
    });
    // Keep-lips needs the landmark model, but a missing one should cost the
    // option, not the swap.
    let mut landmark_detector = if opts.use_mouth_mask {
        match landmark_model {
            Some(path) => match LandmarkDetector::new(path) {
                Ok(d) => Some(d),
                Err(err) => {
                    log_additional!(
                        "load_models",
                        "Landmark model could not be loaded, keep-lips disabled: {err}"
                    );
                    None
                }
            },
            None => {
                log_additional!("load_models", "Keep-lips needs the landmark model; skipping");
                None
            }
        }
    } else {
        None
    };

    let LoadedImages {
        source_images,
        all_source_faces,
        target_array,
        target_faces,
    } = load_and_detect(&mut detector, source_path, target_path, &mut *on_stage)?;

    let mut result = target_array.clone();

    // Build the mapping explicitly instead of prompting on stdin. Multi-face
    // maps the highest-scoring source face onto every detected target face;
    // otherwise it is a single 1:1 swap of the best faces.
    let mappings: Vec<crate::types::FaceMapping> = if opts.use_multi_face && target_faces.len() > 1 {
        log_main!(
            "multi_face",
            "Multi-face mode enabled",
            source_count = all_source_faces.len(),
            target_count = target_faces.len()
        );
        (0..target_faces.len())
            .map(|target_idx| crate::types::FaceMapping {
                source_idx: 0,
                target_idx,
            })
            .collect()
    } else {
        vec![crate::types::FaceMapping {
            source_idx: 0,
            target_idx: 0,
        }]
    };

    let elapsed_s = swap_with_mappings(
        &all_source_faces,
        &target_faces,
        &source_images,
        &mut result,
        &mappings,
        &mut recognizer,
        &mut swapper,
        &mut enhancer,
        &mut landmark_detector,
        opts,
        on_stage,
    )?;
    log_main!(
        "face_swap",
        "Face swap complete",
        faces = mappings.len(),
        elapsed_s = elapsed_s
    );

    on_stage(SwapStage::Saving, 0, 0);

    let result_img = rgb::array3_to_rgb(&result);
    img_io::save_image(&result_img, output_path)?;

    log_main!(EVENT_COMPLETE, "Face swap completed successfully");
    on_stage(SwapStage::Done, 0, 0);
    Ok(())
}

/// Swap faces in video files
///
/// # Arguments
/// * `source_path` - Path to source image(s) - single path or comma-separated list
/// * `target_path` - Path to target video
/// * `output_path` - Path to output video
/// * `detector_path` - Path to detection model
/// * `recognizer_path` - Path to recognition model
/// * `swapper_path` - Path to swapper model
/// * `enhancer_path` - Optional path to enhancement model
/// * `landmark_path` - Optional path to 106-point landmark model
/// * `use_mouth_mask` - Enable mouth mask
/// * `use_multi_face` - Enable multi-face processing with interactive mapping
///
/// # Returns
/// Ok(()) on success
#[cfg(feature = "video")]
pub fn swap_video(
    source_path: &str,
    target_path: &str,
    output_path: &str,
    detector_path: &str,
    recognizer_path: &str,
    swapper_path: &str,
    enhancer_path: Option<&str>,
    landmark_path: Option<&str>,
    use_mouth_mask: bool,
    use_multi_face: bool,
    video_tmp_dir: Option<&str>,
) -> Result<()> {
    log_main!("video_processing", "Starting video face swap");

    log_additional!("load_models", "Loading models");
    let mut detector = FaceDetector::new(detector_path)?;
    let mut recognizer = FaceRecognizer::new(recognizer_path)?;
    let mut swapper = FaceSwapper::new(swapper_path)?;

    let mut enhancer = if let Some(path) = enhancer_path {
        log_additional!("load_models", "Loading enhancement model");
        match FaceEnhancer::new(path) {
            Ok(e) => Some(e),
            Err(err) => {
                log_additional!(
                    "load_models",
                    "Restore model could not be loaded, continuing without it: {err}"
                );
                None
            }
        }
    } else {
        None
    };

    let mut landmark_detector = if let Some(path) = landmark_path {
        log_additional!("load_models", "Loading landmark model");
        match LandmarkDetector::new(path) {
            Ok(d) => Some(d),
            Err(err) => {
                log_additional!(
                    "load_models",
                    "Landmark model could not be loaded, keep-lips disabled: {err}"
                );
                None
            }
        }
    } else {
        None
    };

    // Load and process source images
    let action_start = Instant::now();
    let source_paths: Vec<&str> = source_path.split(',').map(|s| s.trim()).collect();
    let mut source_images: Vec<Array3<u8>> = Vec::new();
    let mut all_source_faces: Vec<SourceFaceInfo> = Vec::new();

    for (img_idx, path) in source_paths.iter().enumerate() {
        log_additional!("load_image", "Loading source image", path = path);

        let img = img_io::load_image(path)?;
        let rgb = img_io::to_rgb8(&img);
        let array = rgb::rgb_to_array3(&rgb);

        let detect_start = Instant::now();
        let faces = detector.detect(&array, 0.5, 0.4)?;

        if faces.is_empty() {
            log_main!("warn", "No faces detected in source image", path = path);
        } else {
            log_main!(
                EVENT_FACE_DETECTED,
                "Detected faces in source image",
                path = path,
                count = faces.len(),
                elapsed_s = detect_start.elapsed().as_secs_f64()
            );

            let filename = std::path::Path::new(path)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or(path);

            for face in faces {
                all_source_faces.push(SourceFaceInfo {
                    face,
                    source_image_index: img_idx,
                    source_filename: filename.to_string(),
                });
            }
        }

        source_images.push(array);
    }

    if all_source_faces.is_empty() {
        return Err(FaceSwapError::NoFacesDetected);
    }

    log_main!(
        "load_source",
        "Loading source images complete",
        count = all_source_faces.len(),
        elapsed_s = action_start.elapsed().as_secs_f64()
    );

    // Extract embeddings from source faces (cached for all frames)
    let action_start = Instant::now();
    let mut source_embeddings = Vec::new();

    if use_multi_face {
        // Multi-face mode: extract embeddings for all source faces
        for info in all_source_faces.iter() {
            log_additional!(
                EVENT_ALIGN_FACE,
                "Aligning source face",
                filename = &info.source_filename
            );
            let source_img = &source_images[info.source_image_index];
            let source_aligned = alignment::align_face(source_img, &info.face, 112)?;
            let embedding = recognizer.extract_embedding(&source_aligned.aligned_image)?;
            source_embeddings.push(embedding);
        }
        log_main!(
            "extract_embeddings",
            "Extracting source face embeddings complete",
            count = source_embeddings.len(),
            elapsed_s = action_start.elapsed().as_secs_f64()
        );
    } else {
        // Single-face mode: extract embedding for first source face
        let source_face_info = &all_source_faces[0];
        let source_face = &source_face_info.face;
        let source_image = &source_images[source_face_info.source_image_index];

        if all_source_faces.len() > 1 {
            log_main!(
                EVENT_FACE_DETECTED,
                "Multiple source faces detected, using highest score",
                filename = &source_face_info.source_filename,
                score = source_face.det_score
            );
        }

        let source_aligned = alignment::align_face(source_image, source_face, 112)?;
        let source_embedding = recognizer.extract_embedding(&source_aligned.aligned_image)?;
        source_embeddings.push(source_embedding);
        log_main!(
            "extract_embeddings",
            "Extracting source face embeddings complete",
            elapsed_s = action_start.elapsed().as_secs_f64()
        );
    }

    // Extract frames from video
    let action_start = Instant::now();
    let base_tmp_dir = video_tmp_dir.unwrap_or("./tmp");
    let frames_dir = format!("{}/video_frames", base_tmp_dir);
    let processed_frames_dir = format!("{}/video_frames_processed", base_tmp_dir);

    create_dir_all(&frames_dir)?;
    create_dir_all(&processed_frames_dir)?;

    let frame_paths = extract_frames(target_path, &frames_dir)?;
    let total_frames = frame_paths.len();

    log_main!(
        "video_extraction",
        "Extraction complete",
        total_frames = total_frames,
        elapsed_s = action_start.elapsed().as_secs_f64()
    );

    log_main!("video_processing", "Starting frame processing");

    let log_interval = std::cmp::max(10, total_frames / 10);
    let processing_start = Instant::now();

    if use_multi_face {
        // Multi-face mode with clustering
        log_main!(
            "multi_face",
            "Multi-face mode for video",
            source_count = all_source_faces.len()
        );

        // scan all frames and extract face embeddings
        let scan_start = Instant::now();
        let mut face_records = scan_frames_for_faces(&frame_paths, &mut detector, &mut recognizer)?;
        log_main!(
            "video_analysis",
            "Frame scanning complete",
            total_faces = face_records.len(),
            elapsed_s = scan_start.elapsed().as_secs_f64()
        );

        // cluster faces across video
        let cluster_start = Instant::now();
        let max_k = 10;
        let centroids = cluster_faces(&mut face_records, max_k)?;
        log_main!(
            "video_analysis",
            "Face clustering complete",
            clusters = centroids.nrows(),
            elapsed_s = cluster_start.elapsed().as_secs_f64()
        );

        // Build cluster info and face lookup
        let cluster_infos = build_cluster_info(&face_records, &centroids)?;
        let face_lookup = build_face_lookup(&face_records);

        // interactive mapping (source faces -> clusters)
        let cluster_mappings = multi_face::build_cluster_mappings(
            &all_source_faces,
            &cluster_infos,
            &source_images,
            &frame_paths,
        )?;
        log_main!(
            "multi_face",
            "Cluster mapping created",
            mappings = cluster_mappings.len()
        );

        // process all frames with cluster mappings
        for (frame_idx, frame_path) in frame_paths.iter().enumerate() {
            let frame_img = img_io::load_image(frame_path)?;
            let frame_rgb = img_io::to_rgb8(&frame_img);
            let mut frame_array = rgb::rgb_to_array3(&frame_rgb);

            // apply each mapping
            for mapping in &cluster_mappings {
                let key = (frame_idx, mapping.cluster_id);
                if let Some(target_faces) = face_lookup.get(&key) {
                    // swap all faces in this cluster at specific frame
                    for target_face in target_faces {
                        let source_info = &all_source_faces[mapping.source_idx];
                        let source_img = &source_images[source_info.source_image_index];
                        let source_embedding = &source_embeddings[mapping.source_idx];

                        let _ = swap_single_pair(
                            &mut frame_array,
                            target_face,
                            source_img,
                            source_embedding,
                            &mut swapper,
                            &mut enhancer,
                            &mut landmark_detector,
                            use_mouth_mask,
                        )?;
                    }
                }
            }

            let processed_frame = rgb::array3_to_rgb(&frame_array);
            let output_frame_path = format!("{}/frame_{:06}.png", processed_frames_dir, frame_idx);
            img_io::save_image_quiet(&processed_frame, &output_frame_path)?;

            if (frame_idx + 1) % log_interval == 0 {
                log_main!(
                    "video_processing",
                    "Processing frames",
                    processed = frame_idx + 1,
                    total = total_frames,
                    elapsed_s = processing_start.elapsed().as_secs_f64()
                );
            }
        }
    } else {
        // Single-face mode with embedding-based tracking
        let source_face_info = &all_source_faces[0];
        let source_image = &source_images[source_face_info.source_image_index];
        let source_embedding = &source_embeddings[0];

        log_main!(
            "video_processing",
            "Single-face mode with embedding-based matching"
        );

        // Extract reference embedding from first detected face
        let mut reference_embedding: Option<Vec<f32>> = None;

        for (frame_idx, frame_path) in frame_paths.iter().enumerate() {
            let frame_img = img_io::load_image(frame_path)?;
            let frame_rgb = img_io::to_rgb8(&frame_img);
            let mut frame_array = rgb::rgb_to_array3(&frame_rgb);

            let target_faces = detector.detect(&frame_array, 0.5, 0.4)?;

            if !target_faces.is_empty() {
                // Initialize reference embedding from first frame
                if reference_embedding.is_none() && !target_faces.is_empty() {
                    let first_face = &target_faces[0];
                    let aligned = alignment::align_face(&frame_array, first_face, 112)?;
                    reference_embedding =
                        Some(recognizer.extract_embedding(&aligned.aligned_image)?);
                }

                // find face most similar to reference embedding
                if let Some(ref ref_emb) = reference_embedding {
                    if let Some(target_face) = crate::utils::embedding::find_most_similar_face(
                        &target_faces,
                        ref_emb,
                        &frame_array,
                        &mut recognizer,
                        0.3,
                    )? {
                        let _ = swap_single_pair(
                            &mut frame_array,
                            target_face,
                            source_image,
                            source_embedding,
                            &mut swapper,
                            &mut enhancer,
                            &mut landmark_detector,
                            use_mouth_mask,
                        )?;
                    }
                }
            }

            let processed_frame = rgb::array3_to_rgb(&frame_array);
            let output_frame_path = format!("{}/frame_{:06}.png", processed_frames_dir, frame_idx);
            img_io::save_image_quiet(&processed_frame, &output_frame_path)?;

            if (frame_idx + 1) % log_interval == 0 {
                log_main!(
                    "video_processing",
                    "Processing frames",
                    processed = frame_idx + 1,
                    total = total_frames,
                    elapsed_s = processing_start.elapsed().as_secs_f64()
                );
            }
        }
    }

    log_main!(
        "video_processing",
        "All frames processed",
        total = total_frames,
        elapsed_s = processing_start.elapsed().as_secs_f64()
    );

    // Encode processed frames back to video
    let action_start = Instant::now();
    crate::video::encode_video(&processed_frames_dir, output_path, target_path)?;
    log_main!(
        "video_encoding",
        "Encoding complete",
        elapsed_s = action_start.elapsed().as_secs_f64()
    );

    // Cleanup temporary directories
    log_additional!("cleanup", "Cleaning up temporary files");
    std::fs::remove_dir_all(&frames_dir)?;
    std::fs::remove_dir_all(&processed_frames_dir)?;

    log_main!(EVENT_COMPLETE, "Video face swap completed successfully");
    Ok(())
}

/// Process video frames with cluster mappings (no interactive prompts)
///
/// Programmatic API for video frame processing. Applies cluster mappings
/// to each frame, calling swap_single_pair for each matched face.
/// Optionally reports progress via callback.
///
/// # Arguments
/// * `frame_paths` - Paths to extracted video frames
/// * `processed_frames_dir` - Directory to save processed frames
/// * `cluster_mappings` - Source-to-cluster mappings
/// * `face_lookup` - Precomputed (frame_idx, cluster_id) -> faces lookup
/// * `all_source_faces` - Source face metadata
/// * `source_images` - Loaded source images
/// * `source_embeddings` - Pre-extracted source embeddings
/// * `swapper` - Face swapper model
/// * `enhancer` - Optional face enhancer model
/// * `landmark_detector` - Optional 106-point landmark detector
/// * `use_mouth_mask` - Whether to apply mouth mask
/// * `progress_callback` - Optional (current_frame, total_frames) callback
#[cfg(feature = "video")]
pub fn swap_video_frames_with_mappings(
    frame_paths: &[String],
    processed_frames_dir: &str,
    cluster_mappings: &[ClusterMapping],
    face_lookup: &HashMap<(usize, usize), Vec<DetectedFace>>,
    all_source_faces: &[SourceFaceInfo],
    source_images: &[Array3<u8>],
    source_embeddings: &[Vec<f32>],
    swapper: &mut FaceSwapper,
    enhancer: &mut Option<FaceEnhancer>,
    landmark_detector: &mut Option<LandmarkDetector>,
    use_mouth_mask: bool,
    progress_callback: Option<&dyn Fn(usize, usize)>,
) -> Result<()> {
    let total_frames = frame_paths.len();
    let log_interval = std::cmp::max(10, total_frames / 10);
    let processing_start = Instant::now();

    log_main!(
        "video_processing",
        "Processing frames started",
        total_frames = total_frames,
        mappings = cluster_mappings.len()
    );

    for (frame_idx, frame_path) in frame_paths.iter().enumerate() {
        let frame_img = img_io::load_image(frame_path)?;
        let frame_rgb = img_io::to_rgb8(&frame_img);
        let mut frame_array = rgb::rgb_to_array3(&frame_rgb);

        for mapping in cluster_mappings {
            let key = (frame_idx, mapping.cluster_id);
            if let Some(target_faces) = face_lookup.get(&key) {
                for target_face in target_faces {
                    let source_info = &all_source_faces[mapping.source_idx];
                    let source_img = &source_images[source_info.source_image_index];
                    let source_embedding = &source_embeddings[mapping.source_idx];

                    let _ = swap_single_pair(
                        &mut frame_array,
                        target_face,
                        source_img,
                        source_embedding,
                        swapper,
                        enhancer,
                        landmark_detector,
                        use_mouth_mask,
                    )?;
                }
            }
        }

        let processed_frame = rgb::array3_to_rgb(&frame_array);
        let output_frame_path = format!("{}/frame_{:06}.png", processed_frames_dir, frame_idx);
        img_io::save_image_quiet(&processed_frame, &output_frame_path)?;

        if let Some(ref cb) = progress_callback {
            cb(frame_idx + 1, total_frames);
        }

        if (frame_idx + 1) % log_interval == 0 {
            log_main!(
                "video_processing",
                "Processing frames",
                processed = frame_idx + 1,
                total = total_frames,
                elapsed_s = processing_start.elapsed().as_secs_f64()
            );
        }
    }

    log_main!(
        "video_processing",
        "All frames processed",
        total = total_frames,
        elapsed_s = processing_start.elapsed().as_secs_f64()
    );

    Ok(())
}

/// Process video frames in single-face mode (no interactive prompts)
///
/// Programmatic API for single-face video processing. Uses embedding-based
/// face tracking to find the target face in each frame.
///
/// # Arguments
/// * `frame_paths` - Paths to extracted video frames
/// * `processed_frames_dir` - Directory to save processed frames
/// * `source_image` - Source image (HWC, RGB, u8)
/// * `source_embedding` - Pre-extracted source face embedding
/// * `detector` - Face detector model
/// * `recognizer` - Face recognizer for embedding matching
/// * `swapper` - Face swapper model
/// * `enhancer` - Optional face enhancer model
/// * `landmark_detector` - Optional 106-point landmark detector
/// * `use_mouth_mask` - Whether to apply mouth mask
/// * `progress_callback` - Optional (current_frame, total_frames) callback
#[cfg(feature = "video")]
pub fn swap_video_frames_single(
    frame_paths: &[String],
    processed_frames_dir: &str,
    source_image: &Array3<u8>,
    source_embedding: &[f32],
    detector: &mut FaceDetector,
    recognizer: &mut FaceRecognizer,
    swapper: &mut FaceSwapper,
    enhancer: &mut Option<FaceEnhancer>,
    landmark_detector: &mut Option<LandmarkDetector>,
    use_mouth_mask: bool,
    progress_callback: Option<&dyn Fn(usize, usize)>,
) -> Result<()> {
    let total_frames = frame_paths.len();
    let log_interval = std::cmp::max(10, total_frames / 10);
    let processing_start = Instant::now();

    let mut reference_embedding: Option<Vec<f32>> = None;

    for (frame_idx, frame_path) in frame_paths.iter().enumerate() {
        let frame_img = img_io::load_image(frame_path)?;
        let frame_rgb = img_io::to_rgb8(&frame_img);
        let mut frame_array = rgb::rgb_to_array3(&frame_rgb);

        let target_faces = detector.detect(&frame_array, 0.5, 0.4)?;

        if !target_faces.is_empty() {
            // Initialize reference embedding from first frame with a face
            if reference_embedding.is_none() {
                let first_face = &target_faces[0];
                let aligned = alignment::align_face(&frame_array, first_face, 112)?;
                reference_embedding = Some(recognizer.extract_embedding(&aligned.aligned_image)?);
            }

            if let Some(ref ref_emb) = reference_embedding {
                if let Some(target_face) = crate::utils::embedding::find_most_similar_face(
                    &target_faces,
                    ref_emb,
                    &frame_array,
                    recognizer,
                    0.3,
                )? {
                    let _ = swap_single_pair(
                        &mut frame_array,
                        target_face,
                        source_image,
                        source_embedding,
                        swapper,
                        enhancer,
                        landmark_detector,
                        use_mouth_mask,
                    )?;
                }
            }
        }

        let processed_frame = rgb::array3_to_rgb(&frame_array);
        let output_frame_path = format!("{}/frame_{:06}.png", processed_frames_dir, frame_idx);
        img_io::save_image_quiet(&processed_frame, &output_frame_path)?;

        if let Some(ref cb) = progress_callback {
            cb(frame_idx + 1, total_frames);
        }

        if (frame_idx + 1) % log_interval == 0 {
            log_main!(
                "video_processing",
                "Processing frames",
                processed = frame_idx + 1,
                total = total_frames,
                elapsed_s = processing_start.elapsed().as_secs_f64()
            );
        }
    }

    log_main!(
        "video_processing",
        "All frames processed",
        total = total_frames,
        elapsed_s = processing_start.elapsed().as_secs_f64()
    );

    Ok(())
}

/// Swap faces using explicit source -> target mappings.
///
/// This is what the app's manual mapping UI calls: the user pairs each source
/// face with a target face and the pairs arrive here as `(source, target)`
/// indices, in detection order (highest score first).
#[allow(clippy::too_many_arguments)]
pub fn swap_faces_mapped(
    source_path: &str,
    target_path: &str,
    output_path: &str,
    detector_model: &str,
    recognizer_model: &str,
    swapper_model: &str,
    enhancer_model: Option<&str>,
    landmark_model: Option<&str>,
    opts: SwapOptions,
    mappings: &[(usize, usize)],
    on_stage: StageCallback,
) -> Result<()> {
    log_main!(
        "swap_init",
        "Initializing mapped face swap",
        source = source_path,
        target = target_path,
        output = output_path,
        pairs = mappings.len()
    );

    on_stage(SwapStage::LoadingModels, 0, 0);
    let mut detector = FaceDetector::new(detector_model)?;
    let mut recognizer = FaceRecognizer::new(recognizer_model)?;
    let mut swapper = FaceSwapper::new(swapper_model)?;
    // A restore model that will not load must not take the swap with it. This
    // used to be `.transpose()?`, so a corrupt or unsupported enhancer aborted
    // the whole job before a single face was touched - the app reported "swap
    // stopped" while the swap itself was fine. The enhancer is optional by
    // definition: without it the output is simply not restored.
    let mut enhancer = enhancer_model.and_then(|path| match FaceEnhancer::new(path) {
        Ok(e) => Some(e),
        Err(err) => {
            log_additional!(
                "load_models",
                "Restore model could not be loaded, continuing without it: {err}"
            );
            None
        }
    });
    // Keep-lips needs the landmark model, but a missing one should cost the
    // option, not the swap.
    let mut landmark_detector = if opts.use_mouth_mask {
        match landmark_model {
            Some(path) => match LandmarkDetector::new(path) {
                Ok(d) => Some(d),
                Err(err) => {
                    log_additional!(
                        "load_models",
                        "Landmark model could not be loaded, keep-lips disabled: {err}"
                    );
                    None
                }
            },
            None => {
                log_additional!("load_models", "Keep-lips needs the landmark model; skipping");
                None
            }
        }
    } else {
        None
    };

    let LoadedImages {
        source_images,
        all_source_faces,
        target_array,
        target_faces,
    } = load_and_detect(&mut detector, source_path, target_path, &mut *on_stage)?;

    // Validate the requested pairs against what was actually detected, so a
    // stale index from the UI can never index out of bounds.
    let mut pairs = Vec::with_capacity(mappings.len());
    for &(source_idx, target_idx) in mappings {
        if source_idx >= all_source_faces.len() || target_idx >= target_faces.len() {
            return Err(FaceSwapError::InvalidInput(format!(
                "Face mapping {}:{} is out of range ({} source faces, {} target faces)",
                source_idx,
                target_idx,
                all_source_faces.len(),
                target_faces.len()
            )));
        }
        pairs.push(crate::types::FaceMapping {
            source_idx,
            target_idx,
        });
    }
    if pairs.is_empty() {
        return Err(FaceSwapError::InvalidInput(
            "No face mappings supplied".to_string(),
        ));
    }

    let mut result = target_array.clone();
    let elapsed_s = swap_with_mappings(
        &all_source_faces,
        &target_faces,
        &source_images,
        &mut result,
        &pairs,
        &mut recognizer,
        &mut swapper,
        &mut enhancer,
        &mut landmark_detector,
        opts,
        on_stage,
    )?;
    log_main!(
        "face_swap",
        "Mapped face swap complete",
        faces = pairs.len(),
        elapsed_s = elapsed_s
    );

    on_stage(SwapStage::Saving, 0, 0);
    let result_img = rgb::array3_to_rgb(&result);
    img_io::save_image(&result_img, output_path)?;

    log_main!(EVENT_COMPLETE, "Face swap completed successfully");
    on_stage(SwapStage::Done, 0, 0);
    Ok(())
}

/// One face offered to the manual mapping UI.
/// Bilinear rescale of an aligned face about its centre.
///
/// Inverse mapping - each output pixel samples where it came from - so there are
/// no gaps. Edges are clamped rather than wrapped, because wrapping would drag
/// the far side of the face into the near side.
fn rescale_face(img: ndarray::Array4<f32>, sx: f32, sy: f32, size: u32) -> ndarray::Array4<f32> {
    use ndarray::Array4;
    let n = size as usize;
    let sx = sx.clamp(0.85, 1.18);
    let sy = sy.clamp(0.85, 1.18);
    let mut out = Array4::<f32>::zeros((1, 3, n, n));
    let centre = (n as f32 - 1.0) / 2.0;
    let max = (n - 1) as f32;

    for y in 0..n {
        for x in 0..n {
            let sxf = ((x as f32 - centre) / sx + centre).clamp(0.0, max);
            let syf = ((y as f32 - centre) / sy + centre).clamp(0.0, max);
            let x0 = sxf.floor() as usize;
            let y0 = syf.floor() as usize;
            let x1 = (x0 + 1).min(n - 1);
            let y1 = (y0 + 1).min(n - 1);
            let fx = sxf - x0 as f32;
            let fy = syf - y0 as f32;
            for c in 0..3usize {
                let p00 = img[[0, c, y0, x0]];
                let p10 = img[[0, c, y0, x1]];
                let p01 = img[[0, c, y1, x0]];
                let p11 = img[[0, c, y1, x1]];
                let top = p00 * (1.0 - fx) + p10 * fx;
                let bottom = p01 * (1.0 - fx) + p11 * fx;
                out[[0, c, y, x]] = top * (1.0 - fy) + bottom * fy;
            }
        }
    }
    out
}

/// Separable box blur over a single-channel plane.
fn box_blur(src: &[f32], n: usize, radius: usize) -> Vec<f32> {
    let r = radius.max(1);
    let mut tmp = vec![0f32; n * n];
    let mut out = vec![0f32; n * n];
    // Horizontal.
    for y in 0..n {
        for x in 0..n {
            let lo = x.saturating_sub(r);
            let hi = (x + r).min(n - 1);
            let mut sum = 0f32;
            for k in lo..=hi {
                sum += src[y * n + k];
            }
            tmp[y * n + x] = sum / (hi - lo + 1) as f32;
        }
    }
    // Vertical.
    for y in 0..n {
        for x in 0..n {
            let lo = y.saturating_sub(r);
            let hi = (y + r).min(n - 1);
            let mut sum = 0f32;
            for k in lo..=hi {
                sum += tmp[k * n + x];
            }
            out[y * n + x] = sum / (hi - lo + 1) as f32;
        }
    }
    out
}

/// Give the swapped face the target's low-frequency shading.
///
/// Intrinsic-image thinking: an image is roughly albedo times shading. Shading is
/// smooth and albedo is detailed, so a wide blur isolates the shading. Keep the
/// swapped face's detail, substitute the target's shading, and the two sit in the
/// same light.
///
/// The ratio is clamped hard. Outside roughly 0.7 to 1.45 the "shading" is no
/// longer lighting but a different exposure or a shadow edge, and applying it
/// would repaint the face rather than light it.
fn relight_to_target(
    swapped: &ndarray::Array4<f32>,
    target: &ndarray::Array4<f32>,
    size: u32,
) -> ndarray::Array4<f32> {
    let n = size as usize;
    let radius = (n / 8).max(2);
    let mut lum_swapped = vec![0f32; n * n];
    let mut lum_target = vec![0f32; n * n];
    for y in 0..n {
        for x in 0..n {
            lum_swapped[y * n + x] = 0.299 * swapped[[0, 0, y, x]]
                + 0.587 * swapped[[0, 1, y, x]]
                + 0.114 * swapped[[0, 2, y, x]];
            lum_target[y * n + x] = 0.299 * target[[0, 0, y, x]]
                + 0.587 * target[[0, 1, y, x]]
                + 0.114 * target[[0, 2, y, x]];
        }
    }
    let shade_swapped = box_blur(&lum_swapped, n, radius);
    let shade_target = box_blur(&lum_target, n, radius);

    let mut out = swapped.clone();
    for y in 0..n {
        for x in 0..n {
            let i = y * n + x;
            let ratio = ((shade_target[i] + 0.02) / (shade_swapped[i] + 0.02)).clamp(0.7, 1.45);
            for c in 0..3usize {
                out[[0, c, y, x]] = (out[[0, c, y, x]] * ratio).clamp(0.0, 1.0);
            }
        }
    }
    out
}

pub struct MappingFace {
    /// Index in detection order (highest score first) — what a mapping refers to.
    pub index: usize,
    /// Detection confidence.
    pub score: f32,
    /// Bounding box in the original image: `[x1, y1, x2, y2]`.
    pub bbox: [f32; 4],
    /// Path to a cropped image of this face, written under `crops_dir`.
    pub crop_path: String,
}

/// Faces detected for the manual mapping UI, with a crop image for each.
pub struct MappingFaces {
    pub source: Vec<MappingFace>,
    pub target: Vec<MappingFace>,
}

/// Detect faces in the source(s) and the target and save a crop of each to
/// `crops_dir/{source,target}/` so a UI can show them for pairing.
pub fn detect_faces_for_mapping(
    source_path: &str,
    target_path: &str,
    detector_model: &str,
    crops_dir: &str,
) -> Result<MappingFaces> {
    let mut detector = FaceDetector::new(detector_model)?;

    let source_paths: Vec<&str> = source_path.split(',').map(|s| s.trim()).collect();
    let mut source_images: Vec<Array3<u8>> = Vec::new();
    let mut all_source_faces: Vec<SourceFaceInfo> = Vec::new();

    for (img_idx, path) in source_paths.iter().enumerate() {
        let img = img_io::load_image(path)?;
        let rgb = img_io::to_rgb8(&img);
        let array = rgb::rgb_to_array3(&rgb);
        let faces = detector.detect(&array, 0.5, 0.4)?;

        let filename = std::path::Path::new(path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or(path);

        for face in faces {
            all_source_faces.push(SourceFaceInfo {
                face,
                source_image_index: img_idx,
                source_filename: filename.to_string(),
            });
        }
        source_images.push(array);
    }

    if all_source_faces.is_empty() {
        return Err(FaceSwapError::NoFacesDetected);
    }

    let source_crops = multi_face::save_face_crops_from_infos_to(
        &all_source_faces,
        &source_images,
        &format!("{}/source", crops_dir),
    )?;

    let target_img = img_io::load_image(target_path)?;
    let target_rgb = img_io::to_rgb8(&target_img);
    let target_array = rgb::rgb_to_array3(&target_rgb);
    let target_faces = detector.detect(&target_array, 0.5, 0.4)?;
    if target_faces.is_empty() {
        return Err(FaceSwapError::NoFacesDetected);
    }

    let target_crops = multi_face::save_face_crops_to(&target_faces, &target_array, false, crops_dir)?;

    Ok(MappingFaces {
        source: to_mapping_faces(source_crops),
        target: to_mapping_faces(target_crops),
    })
}

fn to_mapping_faces(crops: Vec<crate::types::FaceCropInfo>) -> Vec<MappingFace> {
    crops
        .into_iter()
        .map(|crop| MappingFace {
            index: crop.index,
            score: crop.face.det_score,
            bbox: [
                crop.face.bbox.x1,
                crop.face.bbox.y1,
                crop.face.bbox.x2,
                crop.face.bbox.y2,
            ],
            crop_path: crop.crop_path,
        })
        .collect()
}

/// Swap faces across every frame of an animated GIF or WebP.
///
/// Source faces are detected once and reused for every frame; the target faces
/// are re-detected per frame, so a moving face keeps its swap. Output is always
/// written as an animated GIF, because the image crate can decode animated WebP
/// but cannot write it.
///
/// # Arguments
/// * `mappings` - explicit `(source, target)` pairs, or empty to map automatically
/// * `on_stage` - receives `(Swapping, frame_index, frame_count)`, giving a real
///   percentage for animated input
///
/// # Returns
/// The number of frames written
#[allow(clippy::too_many_arguments)]
pub fn swap_animation(
    source_path: &str,
    target_path: &str,
    output_path: &str,
    detector_model: &str,
    recognizer_model: &str,
    swapper_model: &str,
    enhancer_model: Option<&str>,
    landmark_model: Option<&str>,
    opts: SwapOptions,
    mappings: &[(usize, usize)],
    on_stage: StageCallback,
) -> Result<usize> {
    use image::codecs::gif::{GifDecoder, GifEncoder, Repeat};
    use image::codecs::webp::WebPDecoder;
    use image::{AnimationDecoder, Frame};

    on_stage(SwapStage::LoadingModels, 0, 0);
    let mut detector = FaceDetector::new(detector_model)?;
    let mut recognizer = FaceRecognizer::new(recognizer_model)?;
    let mut swapper = FaceSwapper::new(swapper_model)?;
    // A restore model that will not load must not take the swap with it. This
    // used to be `.transpose()?`, so a corrupt or unsupported enhancer aborted
    // the whole job before a single face was touched - the app reported "swap
    // stopped" while the swap itself was fine. The enhancer is optional by
    // definition: without it the output is simply not restored.
    let mut enhancer = enhancer_model.and_then(|path| match FaceEnhancer::new(path) {
        Ok(e) => Some(e),
        Err(err) => {
            log_additional!(
                "load_models",
                "Restore model could not be loaded, continuing without it: {err}"
            );
            None
        }
    });
    // Keep-lips needs the landmark model, but a missing one should cost the
    // option, not the swap.
    let mut landmark_detector = if opts.use_mouth_mask {
        match landmark_model {
            Some(path) => match LandmarkDetector::new(path) {
                Ok(d) => Some(d),
                Err(err) => {
                    log_additional!(
                        "load_models",
                        "Landmark model could not be loaded, keep-lips disabled: {err}"
                    );
                    None
                }
            },
            None => {
                log_additional!("load_models", "Keep-lips needs the landmark model; skipping");
                None
            }
        }
    } else {
        None
    };

    // ---- decode every frame up front so we know the real total
    let ext = std::path::Path::new(target_path)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    let file = std::fs::File::open(target_path)?;
    let reader = std::io::BufReader::new(file);
    let frames: Vec<Frame> = if ext == "webp" {
        WebPDecoder::new(reader)
            .map_err(|e| FaceSwapError::ProcessingError(format!("cannot read webp: {}", e)))?
            .into_frames()
            .collect_frames()
            .map_err(|e| FaceSwapError::ProcessingError(format!("cannot decode frames: {}", e)))?
    } else {
        GifDecoder::new(reader)
            .map_err(|e| FaceSwapError::ProcessingError(format!("cannot read gif: {}", e)))?
            .into_frames()
            .collect_frames()
            .map_err(|e| FaceSwapError::ProcessingError(format!("cannot decode frames: {}", e)))?
    };

    if frames.is_empty() {
        return Err(FaceSwapError::InvalidInput(
            "the animation contains no frames".to_string(),
        ));
    }
    let total = frames.len();

    // ---- source faces are detected once and reused for every frame
    on_stage(SwapStage::LoadingSource, 0, total);
    let source_paths: Vec<&str> = source_path.split(',').map(|s| s.trim()).collect();
    let mut source_images: Vec<Array3<u8>> = Vec::new();
    let mut all_source_faces: Vec<SourceFaceInfo> = Vec::new();

    for (img_idx, path) in source_paths.iter().enumerate() {
        let img = img_io::load_image(path)?;
        let rgb_img = img_io::to_rgb8(&img);
        let array = rgb::rgb_to_array3(&rgb_img);
        let faces = detector.detect(&array, 0.5, 0.4)?;

        let filename = std::path::Path::new(path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or(path);

        for face in faces {
            all_source_faces.push(SourceFaceInfo {
                face,
                source_image_index: img_idx,
                source_filename: filename.to_string(),
            });
        }
        source_images.push(array);
    }

    if all_source_faces.is_empty() {
        return Err(FaceSwapError::NoFacesDetected);
    }

    // ---- swap every frame
    let mut out_frames: Vec<Frame> = Vec::with_capacity(total);
    for (index, frame) in frames.into_iter().enumerate() {
        on_stage(SwapStage::Swapping, index, total);

        let delay = frame.delay();
        let rgba = frame.into_buffer();
        let rgb_frame = image::DynamicImage::ImageRgba8(rgba).to_rgb8();
        let mut array = rgb::rgb_to_array3(&rgb_frame);

        let target_faces = detector.detect(&array, 0.5, 0.4)?;
        if !target_faces.is_empty() {
            let pairs: Vec<crate::types::FaceMapping> = if mappings.is_empty() {
                if opts.use_multi_face && target_faces.len() > 1 {
                    (0..target_faces.len())
                        .map(|target_idx| crate::types::FaceMapping {
                            source_idx: 0,
                            target_idx,
                        })
                        .collect()
                } else {
                    vec![crate::types::FaceMapping {
                        source_idx: 0,
                        target_idx: 0,
                    }]
                }
            } else {
                mappings
                    .iter()
                    .filter(|(s, t)| *s < all_source_faces.len() && *t < target_faces.len())
                    .map(|(s, t)| crate::types::FaceMapping {
                        source_idx: *s,
                        target_idx: *t,
                    })
                    .collect()
            };

            if !pairs.is_empty() {
                // Frame-level progress is reported above, so the per-face callback
                // here is deliberately a no-op.
                let _ = swap_with_mappings(
                    &all_source_faces,
                    &target_faces,
                    &source_images,
                    &mut array,
                    &pairs,
                    &mut recognizer,
                    &mut swapper,
                    &mut enhancer,
                    &mut landmark_detector,
                    opts,
                    &mut |_, _, _| {},
                )?;
            }
        }

        let out_rgb = rgb::array3_to_rgb(&array);
        let out_rgba = image::DynamicImage::ImageRgb8(out_rgb).to_rgba8();
        out_frames.push(Frame::from_parts(out_rgba, 0, 0, delay));
    }

    // ---- encode. Animated WebP cannot be written by the image crate, so the
    // result is always a GIF; the caller is told this in the UI.
    on_stage(SwapStage::Saving, total, total);
    let out_file = std::fs::File::create(output_path)?;
    let writer = std::io::BufWriter::new(out_file);
    let mut encoder = GifEncoder::new(writer);
    encoder
        .set_repeat(Repeat::Infinite)
        .map_err(|e| FaceSwapError::ProcessingError(format!("{}", e)))?;
    for frame in out_frames {
        encoder
            .encode_frame(frame)
            .map_err(|e| FaceSwapError::ProcessingError(format!("cannot encode gif: {}", e)))?;
    }

    log_main!(EVENT_COMPLETE, "Animation swap completed", frames = total);
    on_stage(SwapStage::Done, total, total);
    Ok(total)
}

/// A reusable swap session for frame sequences.
///
/// The models and the source faces are loaded once and reused for every frame.
/// Calling the one-shot image API per frame would reload every ONNX model each
/// time, which is far too slow for video.
pub struct SwapSession {
    detector: FaceDetector,
    recognizer: FaceRecognizer,
    swapper: FaceSwapper,
    enhancer: Option<FaceEnhancer>,
    landmark_detector: Option<LandmarkDetector>,
    source_images: Vec<Array3<u8>>,
    all_source_faces: Vec<SourceFaceInfo>,
}

impl SwapSession {
    /// Load the models and the source face(s) once.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        source_path: &str,
        detector_model: &str,
        recognizer_model: &str,
        swapper_model: &str,
        enhancer_model: Option<&str>,
        landmark_model: Option<&str>,
        use_mouth_mask: bool,
    ) -> Result<Self> {
        let mut detector = FaceDetector::new(detector_model)?;
        let recognizer = FaceRecognizer::new(recognizer_model)?;
        let swapper = FaceSwapper::new(swapper_model)?;
        let enhancer = enhancer_model
            .map(|path| FaceEnhancer::new(path))
            .transpose()?;
        let landmark_detector = if use_mouth_mask {
            match landmark_model {
                Some(path) => match LandmarkDetector::new(path) {
                    Ok(d) => Some(d),
                    Err(err) => {
                        log_additional!(
                            "load_models",
                            "Landmark model could not be loaded, keep-lips disabled: {err}"
                        );
                        None
                    }
                },
                None => {
                    log_additional!("load_models", "Keep-lips needs the landmark model; skipping");
                    None
                }
            }
        } else {
            None
        };

        let source_paths: Vec<&str> = source_path.split(',').map(|s| s.trim()).collect();
        let mut source_images: Vec<Array3<u8>> = Vec::new();
        let mut all_source_faces: Vec<SourceFaceInfo> = Vec::new();

        for (img_idx, path) in source_paths.iter().enumerate() {
            let img = img_io::load_image(path)?;
            let rgb_img = img_io::to_rgb8(&img);
            let array = rgb::rgb_to_array3(&rgb_img);
            let faces = detector.detect(&array, 0.5, 0.4)?;

            let filename = std::path::Path::new(path)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or(path);

            for face in faces {
                all_source_faces.push(SourceFaceInfo {
                    face,
                    source_image_index: img_idx,
                    source_filename: filename.to_string(),
                });
            }
            source_images.push(array);
        }

        if all_source_faces.is_empty() {
            return Err(FaceSwapError::NoFacesDetected);
        }

        log_main!(
            EVENT_LOAD_MODEL,
            "Swap session ready",
            source_faces = all_source_faces.len(),
            swapper_size = swapper.model_size()
        );

        Ok(Self {
            detector,
            recognizer,
            swapper,
            enhancer,
            landmark_detector,
            source_images,
            all_source_faces,
        })
    }

    /// Number of source faces detected when the session was opened.
    pub fn source_face_count(&self) -> usize {
        self.all_source_faces.len()
    }

    /// Swap one frame, reading `frame_in` and writing `frame_out`.
    ///
    /// A frame with no detectable face is copied through unchanged, which is the
    /// right behaviour for cutaways and establishing shots. Returns the number of
    /// faces actually swapped in this frame.
    pub fn swap_frame(
        &mut self,
        frame_in: &str,
        frame_out: &str,
        opts: SwapOptions,
        mappings: &[(usize, usize)],
    ) -> Result<usize> {
        let img = img_io::load_image(frame_in)?;
        let rgb_img = img_io::to_rgb8(&img);
        let mut array = rgb::rgb_to_array3(&rgb_img);
        let swapped = self.swap_array(&mut array, opts, mappings)?;
        let out_rgb = rgb::array3_to_rgb(&array);
        img_io::save_image(&out_rgb, frame_out)?;
        Ok(swapped)
    }

    /// Swap a frame that is already in memory.
    ///
    /// Both transfer formats funnel through here, so the file path and the
    /// buffer path cannot drift apart.
    pub fn swap_array(
        &mut self,
        array: &mut Array3<u8>,
        opts: SwapOptions,
        mappings: &[(usize, usize)],
    ) -> Result<usize> {

        let target_faces = self.detector.detect(array, 0.5, 0.4)?;
        if target_faces.is_empty() {
            // Nothing to swap: the caller writes the array back unchanged.
            return Ok(0);
        }

        let pairs: Vec<crate::types::FaceMapping> = if mappings.is_empty() {
            if opts.use_multi_face && target_faces.len() > 1 {
                (0..target_faces.len())
                    .map(|target_idx| crate::types::FaceMapping {
                        source_idx: 0,
                        target_idx,
                    })
                    .collect()
            } else {
                vec![crate::types::FaceMapping {
                    source_idx: 0,
                    target_idx: 0,
                }]
            }
        } else {
            mappings
                .iter()
                .filter(|(s, t)| *s < self.all_source_faces.len() && *t < target_faces.len())
                .map(|(s, t)| crate::types::FaceMapping {
                    source_idx: *s,
                    target_idx: *t,
                })
                .collect()
        };

        if pairs.is_empty() {
            // Nothing to swap: the caller writes the array back unchanged.
            return Ok(0);
        }

        // Destructure so the immutable and mutable borrows of self do not overlap.
        let Self {
            recognizer,
            swapper,
            enhancer,
            landmark_detector,
            all_source_faces,
            source_images,
            ..
        } = self;

        let swapped = pairs.len();
        let _ = swap_with_mappings(
            all_source_faces,
            &target_faces,
            source_images,
            array,
            &pairs,
            recognizer,
            swapper,
            enhancer,
            landmark_detector,
            opts,
            &mut |_, _, _| {},
        )?;

        Ok(swapped)
    }

    /// Swap a frame handed over as RGBA bytes and give RGBA bytes back.
    ///
    /// This is the video path. It replaces four PNG operations per frame — an
    /// encode and a decode on each side of the call — with two memory copies,
    /// which is the single largest saving available per frame.
    pub fn swap_frame_rgba(
        &mut self,
        width: usize,
        height: usize,
        rgba: &[u8],
        opts: SwapOptions,
        mappings: &[(usize, usize)],
    ) -> Result<(Vec<u8>, usize)> {
        let mut array = Array3::<u8>::zeros((height, width, 3));
        for y in 0..height {
            for x in 0..width {
                let src = (y * width + x) * 4;
                array[[y, x, 0]] = rgba[src];
                array[[y, x, 1]] = rgba[src + 1];
                array[[y, x, 2]] = rgba[src + 2];
            }
        }
        let swapped = self.swap_array(&mut array, opts, mappings)?;
        let mut out = vec![0u8; width * height * 4];
        for y in 0..height {
            for x in 0..width {
                let dst = (y * width + x) * 4;
                out[dst] = array[[y, x, 0]];
                out[dst + 1] = array[[y, x, 1]];
                out[dst + 2] = array[[y, x, 2]];
                out[dst + 3] = 255;
            }
        }
        Ok((out, swapped))
    }
}
