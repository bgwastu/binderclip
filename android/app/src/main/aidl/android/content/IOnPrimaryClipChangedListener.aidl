package android.content;

// Mirrors the framework's hidden listener; the shell-uid user service registers it with IClipboard.
oneway interface IOnPrimaryClipChangedListener {
    void dispatchPrimaryClipChanged();
}
