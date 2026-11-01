//! Rust implementation of the Chronos backup utility.
//! Smart stuff we do here:
//! Use mca and na_nbt for maximum performance
//! Copy files in parallel using rayon
//! Zip the snapshot using rawzip, and instead of writing back to disk (cache) we stream the pruned files directly into the zip

mod git_backup;
mod jni_log;
mod pruner;
mod snapshot_zip;
mod source_hash;
mod world_copy;

#[cfg(feature = "world-test")]
mod world_test;

use crate::jni_log::{log_error, log_info};
use std::io::ErrorKind;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::Arc;

use jni::objects::{JClass, JIntArray, JObjectArray, JStaticMethodID, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jint, jintArray, jobjectArray, jstring};
use jni::JNIEnv;

#[cfg(feature = "world-test")]
pub fn run_prune_world_test(world_name: Option<&str>) -> i32 {
    world_test::run(world_name)
}

pub fn prune_world_folder<F>(
    world_folder: PathBuf,
    inhabited_time_seconds_required: u64,
    max_worker_threads: usize,
    mut log_info: F,
) -> Result<(), std::io::Error>
where
    F: FnMut(&str),
{
    if inhabited_time_seconds_required == 0 {
        return Ok(());
    }
    pruner::prune_world(
        world_folder,
        inhabited_time_seconds_required,
        max_worker_threads,
        None,
        &mut log_info,
    )
}

fn prune_world_to_zip_impl(
    world_folder: PathBuf,
    zip_path: PathBuf,
    inhabited_time_seconds_required: u64,
    max_worker_threads: usize,
    env: &mut JNIEnv,
    clazz: &JClass,
    poll_mid: JStaticMethodID,
) -> jint {
    let run_prune = inhabited_time_seconds_required > 0;

    let sink = match snapshot_zip::ZipStreamSink::create(world_folder.clone(), zip_path) {
        Ok(s) => Arc::new(s),
        Err(err) => {
            log_error(env, clazz, &format!("Chronos backups: failed to create zip output: {err}"));
            return 1;
        }
    };

    if run_prune {
        if let Err(err) = pruner::prune_world(
            world_folder.clone(),
            inhabited_time_seconds_required,
            max_worker_threads,
            Some(sink.clone()),
            &mut |message| log_info(env, clazz, message),
        ) {
            log_error(env, clazz, &format!("Chronos backups: failed to prune world: {err}"));
            return 1;
        }
    }

    let mut poll_abort = || unsafe {
        match env.call_static_method_unchecked(
            clazz,
            poll_mid,
            ReturnType::Primitive(Primitive::Boolean),
            &[],
        ) {
            Ok(v) => v.z().unwrap_or(false),
            Err(err) => {
                eprintln!("Warning: pollAbortCopy failed: {err}");
                false
            }
        }
    };

    if let Err(err) =
        snapshot_zip::append_remaining_snapshot_files(sink.as_ref(), &mut poll_abort)
    {
        if err.kind() == ErrorKind::Interrupted {
            return 2;
        }
        log_error(env, clazz, &format!("Chronos backups: failed to zip snapshot remainder: {err}"));
        return 1;
    }

    if let Err(err) = sink.as_ref().finish() {
        log_error(env, clazz, &format!("Chronos backups: failed to finalize zip archive: {err}"));
        return 1;
    }

    return 0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_pruneWorldNative(
    mut env: JNIEnv,
    _class: JClass,
    world_folder: JString,
    inhabited_time_seconds_required: jint,
    max_worker_threads: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let world_folder_str = match env.get_string(&world_folder) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(err) => {
                eprintln!("Error: failed to decode world path from Java: {err}");
                return 4;
            }
        };
        let seconds = if inhabited_time_seconds_required < 0 {
            0
        } else {
            inhabited_time_seconds_required as u64
        };
        let threads = if max_worker_threads <= 0 {
            0
        } else {
            max_worker_threads as usize
        };

        let clazz = match env.find_class("com/magicjinn/chronos/core/RustPrunerBridge") {
            Ok(c) => c,
            Err(err) => {
                eprintln!("Error: find_class RustPrunerBridge: {err}");
                return 4;
            }
        };

        match prune_world_folder(
            PathBuf::from(world_folder_str),
            seconds,
            threads,
            |message| log_info(&mut env, &clazz, message),
        ) {
            Ok(()) => 0,
            Err(err) => {
                log_error(
                    &mut env,
                    &clazz,
                    &format!("Chronos backups: failed to prune world folder: {err}"),
                );
                1
            }
        }
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "rust-pruner prune panicked");
            3
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_pruneWorldToZipNative(
    mut env: JNIEnv,
    _class: JClass,
    world_folder: JString,
    zip_path: JString,
    inhabited_time_seconds_required: jint,
    max_worker_threads: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let world_folder_str = match env.get_string(&world_folder) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(err) => {
                eprintln!("Error: failed to decode world path from Java: {err}");
                return 4;
            }
        };
        let zip_path_str = match env.get_string(&zip_path) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(err) => {
                eprintln!("Error: failed to decode zip path from Java: {err}");
                return 4;
            }
        };
        let seconds = if inhabited_time_seconds_required < 0 {
            0
        } else {
            inhabited_time_seconds_required as u64
        };
        let threads = if max_worker_threads <= 0 {
            0
        } else {
            max_worker_threads as usize
        };

        let clazz = match env.find_class("com/magicjinn/chronos/core/RustPrunerBridge") {
            Ok(c) => c,
            Err(err) => {
                eprintln!("Error: find_class RustPrunerBridge: {err}");
                return 4;
            }
        };
        let poll_mid = match env.get_static_method_id(&clazz, "pollAbortCopy", "()Z") {
            Ok(m) => m,
            Err(err) => {
                eprintln!("Error: get_static_method_id pollAbortCopy: {err}");
                return 4;
            }
        };

        prune_world_to_zip_impl(
            PathBuf::from(world_folder_str),
            PathBuf::from(zip_path_str),
            seconds,
            threads,
            &mut env,
            &clazz,
            poll_mid,
        )
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "rust-pruner prune+zip panicked");
            3
        }
    }
}

const COPY_ABORT_POLL_FILES: usize = 2048;

fn parse_jstring_array(env: &mut JNIEnv, arr: jobjectArray) -> Result<Vec<String>, jni::errors::Error> {
    if arr.is_null() {
        return Ok(Vec::new());
    }
    let arr = unsafe { JObjectArray::from_raw(arr) };
    let len = env.get_array_length(&arr)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let elem = env.get_object_array_element(&arr, i)?;
        if elem.is_null() {
            continue;
        }
        let js = JString::from(elem);
        let s = env.get_string(&js)?;
        out.push(s.to_string_lossy().into_owned());
    }
    Ok(out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_copyWorldToCacheNative(
    mut env: JNIEnv,
    _class: JClass,
    world_root: JString,
    cache_dest: JString,
    blacklist: jobjectArray,
    max_copy_worker_threads: jint,
    out_copied_file_count: jintArray,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let world_str = match env.get_string(&world_root) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(err) => {
                eprintln!("Error: failed to decode world path from Java: {err}");
                return 4;
            }
        };
        let dest_str = match env.get_string(&cache_dest) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(err) => {
                eprintln!("Error: failed to decode cache dest path from Java: {err}");
                return 4;
            }
        };
        let blacklist = match parse_jstring_array(&mut env, blacklist) {
            Ok(b) => b,
            Err(err) => {
                eprintln!("Error: failed to read copy blacklist from Java: {err}");
                return 4;
            }
        };
        let threads = if max_copy_worker_threads <= 0 {
            0
        } else {
            max_copy_worker_threads as usize
        };

        let clazz = match env.find_class("com/magicjinn/chronos/core/RustPrunerBridge") {
            Ok(c) => c,
            Err(err) => {
                eprintln!("Error: find_class RustPrunerBridge: {err}");
                return 4;
            }
        };
        let poll_mid = match env.get_static_method_id(&clazz, "pollAbortCopy", "()Z") {
            Ok(m) => m,
            Err(err) => {
                eprintln!("Error: get_static_method_id pollAbortCopy: {err}");
                return 4;
            }
        };

        let world_path = PathBuf::from(world_str);
        let dest_path = PathBuf::from(dest_str);

        let copy_jobs = match world_copy::build_copy_plan(&world_path, &dest_path, &blacklist) {
            Ok(jobs) => jobs,
            Err(err) if err.kind() == ErrorKind::PermissionDenied => {
                eprintln!("Error: copy plan permission / path layout: {err}");
                return 5;
            }
            Err(err) => {
                eprintln!("Error: copy plan failed: {err}");
                return 1;
            }
        };

        let total = copy_jobs.len();
        for chunk in copy_jobs.chunks(COPY_ABORT_POLL_FILES) {
            let abort = match unsafe {
                env.call_static_method_unchecked(
                    &clazz,
                    &poll_mid,
                    ReturnType::Primitive(Primitive::Boolean),
                    &[],
                )
            } {
                Ok(v) => v.z().unwrap_or(false),
                Err(err) => {
                    eprintln!("Warning: pollAbortCopy failed: {err}");
                    false
                }
            };
            if abort {
                return 2;
            }
            if let Err(err) = world_copy::parallel_copy_file_chunk(chunk, threads) {
                eprintln!("Error: parallel copy failed: {err}");
                return 1;
            }
        }

        if !out_copied_file_count.is_null() {
            let out_arr = unsafe { JIntArray::from_raw(out_copied_file_count) };
            if let Err(err) = env.set_int_array_region(&out_arr, 0, &[total as jint]) {
                eprintln!("Error: set_int_array_region: {err}");
                return 4;
            }
        }

        0
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "rust-pruner world copy panicked");
            3
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_getNativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(version) => version.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Hashes live world sources with xxHash3, compares to a previous files JSON map,
/// and writes a scan-result JSON file for Java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_scanWorldSourcesNative(
    mut env: JNIEnv,
    _class: JClass,
    world_root: JString,
    blacklist: jobjectArray,
    previous_files_json: JString,
    prune_policy_hash: JString,
    force_all: jint,
    result_json_path: JString,
    max_worker_threads: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let world_str = match env.get_string(&world_root) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let prev_json = match env.get_string(&previous_files_json) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let policy = match env.get_string(&prune_policy_hash) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let out_path = match env.get_string(&result_json_path) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let blacklist = match parse_jstring_array(&mut env, blacklist) {
            Ok(b) => b,
            Err(_) => return 4,
        };
        let threads = if max_worker_threads <= 0 {
            0
        } else {
            max_worker_threads as usize
        };
        let previous = source_hash::fingerprints_from_json_object(&prev_json);
        let scan = match source_hash::scan_world_sources(
            PathBuf::from(world_str).as_path(),
            &blacklist,
            &previous,
            force_all != 0,
            threads,
        ) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let json = source_hash::scan_result_to_json(&scan, &policy);
        if let Err(_) = std::fs::write(PathBuf::from(out_path), json.as_bytes()) {
            return 1;
        }
        0
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "rust-pruner source scan panicked");
            3
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_copySelectedFilesNative(
    mut env: JNIEnv,
    _class: JClass,
    world_root: JString,
    dest_root: JString,
    relative_paths: jobjectArray,
    max_copy_worker_threads: jint,
    out_copied_file_count: jintArray,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let world_str = match env.get_string(&world_root) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let dest_str = match env.get_string(&dest_root) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let paths = match parse_jstring_array(&mut env, relative_paths) {
            Ok(b) => b,
            Err(_) => return 4,
        };
        let threads = if max_copy_worker_threads <= 0 {
            0
        } else {
            max_copy_worker_threads as usize
        };
        match world_copy::copy_selected_files(
            PathBuf::from(world_str).as_path(),
            PathBuf::from(dest_str).as_path(),
            &paths,
            threads,
        ) {
            Ok(total) => {
                if !out_copied_file_count.is_null() {
                    let out_arr = unsafe { JIntArray::from_raw(out_copied_file_count) };
                    if env.set_int_array_region(&out_arr, 0, &[total as jint]).is_err() {
                        return 4;
                    }
                }
                0
            }
            Err(err) if err.kind() == ErrorKind::PermissionDenied => 5,
            Err(_) => 1,
        }
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "rust-pruner selected copy panicked",
            );
            3
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_gitInitNative(
    mut env: JNIEnv,
    _class: JClass,
    worktree: JString,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = match env.get_string(&worktree) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        match git_backup::init_repo(PathBuf::from(path).as_path()) {
            Ok(()) => 0,
            Err(_) => 1,
        }
    }));
    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "gitoxide init panicked");
            3
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_gitCommitAndTagNative(
    mut env: JNIEnv,
    _class: JClass,
    worktree: JString,
    backup_id: JString,
    message: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = match env.get_string(&worktree) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return std::ptr::null_mut(),
        };
        let id = match env.get_string(&backup_id) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return std::ptr::null_mut(),
        };
        let msg = match env.get_string(&message) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return std::ptr::null_mut(),
        };
        match git_backup::commit_and_tag(PathBuf::from(path).as_path(), &id, &msg) {
            Ok(sha) => match env.new_string(sha) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            Err(err) => {
                let _ = env.throw_new(
                    "java/io/IOException",
                    &format!("gitoxide commit failed: {err}"),
                );
                std::ptr::null_mut()
            }
        }
    }));
    match result {
        Ok(s) => s,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "gitoxide commit panicked");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_gitListBackupTagsNative(
    mut env: JNIEnv,
    _class: JClass,
    worktree: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = match env.get_string(&worktree) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return std::ptr::null_mut(),
        };
        match git_backup::list_backup_tags(PathBuf::from(path).as_path()) {
            Ok(tags) => match env.new_string(tags.join("\n")) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            Err(err) => {
                let _ = env.throw_new(
                    "java/io/IOException",
                    &format!("gitoxide list tags failed: {err}"),
                );
                std::ptr::null_mut()
            }
        }
    }));
    match result {
        Ok(s) => s,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "gitoxide list tags panicked");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_magicjinn_chronos_core_RustPrunerBridge_gitExportTagNative(
    mut env: JNIEnv,
    _class: JClass,
    worktree: JString,
    tag_name: JString,
    dest: JString,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = match env.get_string(&worktree) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let tag = match env.get_string(&tag_name) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        let dest_path = match env.get_string(&dest) {
            Ok(s) => s.to_string_lossy().into_owned(),
            Err(_) => return 4,
        };
        match git_backup::restore_tag(
            PathBuf::from(path).as_path(),
            &tag,
            PathBuf::from(dest_path).as_path(),
        ) {
            Ok(()) => 0,
            Err(_) => 1,
        }
    }));
    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "gitoxide export panicked");
            3
        }
    }
}
