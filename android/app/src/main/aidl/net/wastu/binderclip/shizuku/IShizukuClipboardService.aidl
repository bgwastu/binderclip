package net.wastu.binderclip.shizuku;

import net.wastu.binderclip.shizuku.IClipboardWatcher;

interface IShizukuClipboardService {
    // Shizuku's reserved transaction for UserService teardown.
    void destroy() = 16777114;

    // Returns false when the platform refuses the listener or the read.
    boolean startWatching(IClipboardWatcher watcher, int userId) = 1;
    void stopWatching() = 2;
    // Re-dispatches the current clip to the watcher, for bootstrap and the setup self-test.
    boolean dispatchCurrent() = 3;
}
