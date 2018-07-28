package com.termux.filepicker;

import com.termux.app.TermuxService;

public class TermuxBackgroundFileReceiverActivity extends TermuxFileReceiverActivity {

    @Override
    protected void onResume() {
        EDITOR_PROGRAM_BASE = "/bin/termux-file-editor-task";
        EDITOR_PROGRAM = TermuxService.HOME_PATH + EDITOR_PROGRAM_BASE;
        URL_OPENER_PROGRAM_BASE = "/bin/termux-url-opener-task";
        URL_OPENER_PROGRAM = TermuxService.HOME_PATH + URL_OPENER_PROGRAM_BASE;
        IS_TASK = true;

        super.onResume();
    }

}
