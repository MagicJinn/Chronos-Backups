mod pruner;

use std::io::Read;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::{io::Cursor, path::PathBuf};

use flate2::read::GzDecoder;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
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
