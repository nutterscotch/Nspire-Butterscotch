#include "nspire_file_system.h"
#include "common.h"
#include "utils.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    FileSystem base;
    char* baseDir; // owned; ends with '/'
} NspireFileSystem;

// ===[ Path mapping ]===

// Game-relative name -> persistent device path. GameMaker may hand us bare names
// ("file0"), "./"-prefixed names (working_directory is "./"), or, in theory, a
// path with separators. Undertale only ever uses flat names, so collapse to the
// basename, sanitize it for the Nspire filesystem, and pin it under baseDir with
// the mandatory ".tns" suffix and a "sav_" prefix.
static char* nfsBuildPath(NspireFileSystem* nfs, const char* rel) {
    if (rel == nullptr) rel = "";

    // Basename: text after the last path separator.
    const char* base = rel;
    for (const char* p = rel; *p; p++) {
        if (*p == '/' || *p == '\\') base = p + 1;
    }
    if (*base == '\0') base = "save"; // path ended in a separator — shouldn't happen

    size_t baseLen = strlen(base);
    size_t dirLen = strlen(nfs->baseDir);
    // baseDir + "sav_" + sanitized base + ".tns" + NUL
    char* out = safeMalloc(dirLen + 4 + baseLen + 4 + 1);

    char* w = out;
    memcpy(w, nfs->baseDir, dirLen); w += dirLen;
    memcpy(w, "sav_", 4);            w += 4;
    for (size_t i = 0; i < baseLen; i++) {
        unsigned char c = (unsigned char) base[i];
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                  (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
        *w++ = ok ? (char) c : '_';
    }
    memcpy(w, ".tns", 4); w += 4;
    *w = '\0';
    return out;
}

// Reads an entire file. Returns malloc'd buffer (NUL-terminated, terminator not
// counted in *outSize) or nullptr if the file does not exist / cannot be read.
static uint8_t* nfsReadAll(const char* path, int32_t* outSize) {
    FILE* f = fopen(path, "rb");
    if (f == nullptr) return nullptr;

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size < 0) { fclose(f); return nullptr; }

    uint8_t* data = safeMalloc((size_t) size + 1);
    size_t got = fread(data, 1, (size_t) size, f);
    fclose(f);
    data[got] = '\0';
    if (outSize) *outSize = (int32_t) got;
    return data;
}

static bool nfsWriteAll(const char* path, const void* data, size_t size) {
    FILE* f = fopen(path, "wb");
    if (f == nullptr) return false;
    size_t written = (size > 0) ? fwrite(data, 1, size, f) : 0;
    fclose(f);
    return written == size;
}

// ===[ Vtable ]===

static char* nfsResolvePath(FileSystem* fs, const char* relativePath) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    // working_directory query: mirror NoopFileSystem ("./"). Callers concatenate
    // this with a filename and hand it back to us; nfsBuildPath basenames it.
    if (relativePath == nullptr || relativePath[0] == '\0')
        return safeStrdup("./");
    return nfsBuildPath(nfs, relativePath);
}

static bool nfsFileExists(FileSystem* fs, const char* relativePath) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    char* path = nfsBuildPath(nfs, relativePath);
    FILE* f = fopen(path, "rb");
    free(path);
    if (f == nullptr) return false;
    fclose(f);
    return true;
}

static char* nfsReadFileText(FileSystem* fs, const char* relativePath) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    char* path = nfsBuildPath(nfs, relativePath);
    uint8_t* data = nfsReadAll(path, nullptr);
    free(path);
    return (char*) data; // NUL-terminated by nfsReadAll; nullptr if missing
}

static bool nfsWriteFileText(FileSystem* fs, const char* relativePath, const char* contents) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    char* path = nfsBuildPath(nfs, relativePath);
    bool ok = nfsWriteAll(path, contents, contents ? strlen(contents) : 0);
    free(path);
    return ok;
}

static bool nfsDeleteFile(FileSystem* fs, const char* relativePath) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    char* path = nfsBuildPath(nfs, relativePath);
    bool ok = remove(path) == 0;
    free(path);
    return ok;
}

static bool nfsReadFileBinary(FileSystem* fs, const char* relativePath,
                              uint8_t** outData, int32_t* outSize) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    char* path = nfsBuildPath(nfs, relativePath);
    int32_t size = 0;
    uint8_t* data = nfsReadAll(path, &size);
    free(path);
    if (data == nullptr) return false;
    *outData = data; // caller frees; trailing NUL is harmless extra byte
    *outSize = size;
    return true;
}

static bool nfsWriteFileBinary(FileSystem* fs, const char* relativePath,
                               const uint8_t* data, int32_t size) {
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    char* path = nfsBuildPath(nfs, relativePath);
    bool ok = nfsWriteAll(path, data, size > 0 ? (size_t) size : 0);
    free(path);
    return ok;
}

static FileSystemVtable nspireFileSystemVtable = {
    .resolvePath = nfsResolvePath,
    .fileExists = nfsFileExists,
    .readFileText = nfsReadFileText,
    .writeFileText = nfsWriteFileText,
    .deleteFile = nfsDeleteFile,
    .readFileBinary = nfsReadFileBinary,
    .writeFileBinary = nfsWriteFileBinary,
};

// ===[ Lifecycle ]===

FileSystem* NspireFileSystem_create(const char* baseDir) {
    NspireFileSystem* nfs = safeCalloc(1, sizeof(NspireFileSystem));
    nfs->base.vtable = &nspireFileSystemVtable;

    const char* dir = (baseDir && baseDir[0]) ? baseDir : "/documents/bs/";
    size_t n = strlen(dir);
    bool needSlash = (n == 0 || dir[n - 1] != '/');
    nfs->baseDir = safeMalloc(n + (needSlash ? 1 : 0) + 1);
    memcpy(nfs->baseDir, dir, n);
    if (needSlash) nfs->baseDir[n++] = '/';
    nfs->baseDir[n] = '\0';

    return (FileSystem*) nfs;
}

void NspireFileSystem_destroy(FileSystem* fs) {
    if (fs == nullptr) return;
    NspireFileSystem* nfs = (NspireFileSystem*) fs;
    free(nfs->baseDir);
    free(nfs);
}
