package com.stroexd.hsdecktracker.powerlog;

import android.os.Bundle;

interface IPowerLogReader {
    Bundle readChunk(String fileId, long offset, boolean attachAtEnd, String deckFileId, long deckOffset) = 1;
    int enableGameLogging() = 2;
    void destroy() = 16777114;
}
