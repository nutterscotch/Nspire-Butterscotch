// Link-test stub. Just calls into a handful of engine functions to force the
// linker to pull symbols. We're not yet trying to actually run anything — the
// point is to surface every undefined symbol so we know what platform glue we
// still owe the engine.

#include <libndls.h>
#include <stdint.h>
#include <stdio.h>

#include "data_win.h"

int main(void) {
    DataWinParserOptions opts = (DataWinParserOptions) {
        .parseGen8 = true,
        .parseOptn = false, .parseLang = false, .parseExtn = false,
        .parseSond = false, .parseAgrp = false,
        .parseSprt = true,  .parseBgnd = true,
        .parsePath = false, .parseScpt = false, .parseGlob = false, .parseShdr = false,
        .parseFont = true,  .parseTmln = false, .parseObjt = true,
        .parseRoom = true,  .parseTpag = true,
        .parseCode = true,  .parseVari = true, .parseFunc = true,
        .parseStrg = true,  .parseTxtr = false,
        .parseAudo = false,
        .skipLoadingPreciseMasksForNonPreciseSprites = true,
    };
    (void) opts;
    return 0;
}
