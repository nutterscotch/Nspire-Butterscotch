#pragma once

#include "file_system.h"

// Real disk-backed FileSystem for the TI-Nspire (Ndless newlib stdio).
//
// GameMaker save/load (ini_*, file_text_*, game_save/load) all route through the
// FileSystem vtable. Undertale uses flat relative names ("undertale.ini", "file0",
// "file8", "file9"). We map each to a single persistent file in `baseDir`:
//
//     <baseDir>sav_<sanitized-basename>.tns
//
// The ".tns" suffix is mandatory: the Nspire OS only persists/exposes files with
// that extension. The "sav_" prefix keeps saves from colliding with the cooked
// asset files that live in the same directory and makes them easy to spot/delete.
//
// `baseDir` must already exist and end with '/'. Pass the asset dir (it is known
// to exist because the cooked sidecars were loaded from it).
FileSystem* NspireFileSystem_create(const char* baseDir);
void NspireFileSystem_destroy(FileSystem* fs);
