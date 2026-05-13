mod pruner;
mod world_copy;

use std::io::Read;
use std::io::ErrorKind;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::{io::Cursor, path::PathBuf};

use flate2::read::GzDecoder;
use jni::objects::{JClass, JIntArray, JObjectArray, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jint, jintArray, jobjectArray, jstring};
use jni::JNIEnv;

pub fn prune_world_folder(
    world_folder: PathBuf,
    inhabited_time_seconds_required: u64,
    max_worker_threads: usize,
) -> Result<(), std::io::Error> {
    let data_version = get_data_version(world_folder.clone());
    if data_version == 0 {
        return Ok(());
    }
    pruner::prune_world(
        world_folder,
        data_version,
        inhabited_time_seconds_required,
        max_worker_threads,
    )
}

fn get_data_version(world_folder: PathBuf) -> u32 {
    let level_dat = world_folder.join("level.dat");
    if !level_dat.is_file() {
        eprintln!("Error: level.dat not found");
        return 0;
    }

    let file_bytes = match std::fs::read(level_dat) {
        Ok(file_bytes) => file_bytes,
        Err(e) => {
            eprintln!("Error: failed to open level.dat: {e}");
            return 0;
        }
    };

    let mut decoder = GzDecoder::new(file_bytes.as_slice());
    let mut decompressed = Vec::new();
    if let Err(e) = decoder.read_to_end(&mut decompressed) {
        eprintln!("Error: failed to decompress level.dat: {e}");
        return 0;
    }

    let nbt = match simdnbt::borrow::read(&mut Cursor::new(decompressed.as_slice())) {
        Ok(nbt) => nbt,
        Err(e) => {
            eprintln!("Error: failed to read level.dat: {e}");
            return 0;
        }
    };

    nbt.unwrap()
        .compound("Data")
        .and_then(|data| data.int("DataVersion"))
        .unwrap_or(0) as u32
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
                return 2;
            }
        };
        let world_folder_path = PathBuf::from(world_folder_str);
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

        match prune_world_folder(world_folder_path, seconds, threads) {
            Ok(()) => 0,
            Err(err) => {
                eprintln!("Error: failed to prune world: {err}");
                1
            }
        }
    }));

    match result {
        Ok(code) => code,
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "rust-pruner panicked");
            3
        }
    }
}

const COPY_ABORT_POLL_FILES: usize = 2048;

fn parse_copy_blacklist(env: &mut JNIEnv, arr: jobjectArray) -> Result<Vec<String>, jni::errors::Error> {
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
        let blacklist = match parse_copy_blacklist(&mut env, blacklist) {
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
