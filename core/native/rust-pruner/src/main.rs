mod pruner;
use std::io::Read;
use std::{io::Cursor, path::PathBuf};
use std::time::Instant;

use flate2::read::GzDecoder;

fn main() {
    let start = Instant::now();
    println!("Hello, world!");

    // Check for a folder called "world" next to us (executable)
    let world_folder = std::env::current_dir().unwrap().join("world");
    if !world_folder.exists() {
        eprintln!("Error: World folder not found");
        println!("Total elapsed: {:?}", start.elapsed());
        std::process::exit(1);
    }

    // Print the world folder
    println!("World folder: {}", world_folder.display());
    let data_version = get_data_version(world_folder.clone());
    println!("Data version: {}", data_version);
    // Prune the world
    if data_version != 0 {
        if let Err(err) = pruner::prune_world(world_folder, data_version, 60 * 5) {
            eprintln!("Error: failed to prune world: {}", err);
            println!("Total elapsed: {:?}", start.elapsed());
            std::process::exit(1);
        }
    } else {
        eprintln!("Error: failed to get data version");
        println!("Total elapsed: {:?}", start.elapsed());
        std::process::exit(1);
    }

    // // also run on New World
    // let new_world_folder = std::env::current_dir().unwrap().join("New World");
    // if !new_world_folder.exists() {
    //     eprintln!("Error: New World folder not found");
    //     std::process::exit(1);
    // }
    // println!("New World folder: {}", new_world_folder.display());
    // pruner::prune_world(new_world_folder, 1, 60 * 5);

    println!("Total elapsed: {:?}", start.elapsed());
}

fn get_data_version(world_folder: PathBuf) -> u32 {
    // get the data version from the world/level.dat file
    let level_dat = world_folder.join("level.dat");
    if !level_dat.is_file() {
        eprintln!("Error: level.dat not found");
        return 0;
    }

    let file_bytes = match std::fs::read(level_dat) {
        Ok(file_bytes) => file_bytes,
        Err(e) => {
            eprintln!("Error: failed to open level.dat: {}", e);
            return 0;
        }
    };

    let mut decoder = GzDecoder::new(file_bytes.as_slice());
    let mut decompressed = Vec::new();
    if let Err(e) = decoder.read_to_end(&mut decompressed) {
        eprintln!("Error: failed to decompress level.dat: {}", e);
        return 0;
    }

    let nbt = match simdnbt::borrow::read(&mut Cursor::new(decompressed.as_slice())) {
        Ok(nbt) => nbt,
        Err(e) => {
            eprintln!("Error: failed to read level.dat: {}", e);
            return 0;
        }
    };

    return nbt
        .unwrap()
        .compound("Data")
        .and_then(|data| data.int("DataVersion"))
        .unwrap_or(0) as u32;
}
