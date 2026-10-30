package net.wastu.binderclip.shizuku;

import android.os.ParcelFileDescriptor;

// Callbacks from the shell-uid user service into BinderClip. `token` is the clip's
// ClipDescription timestamp, so re-copying identical content still counts as a change.
oneway interface IClipboardWatcher {
    void onText(String text, long token);
    void onImage(in ParcelFileDescriptor data, String mimeType, long size, long token);
    void onUnsupported(long token);
}
