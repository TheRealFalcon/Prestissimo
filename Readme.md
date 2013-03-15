# Prestissimo

Prestissimo is a free (as in beer) and open source drop-in replacement for the Presto Sound Library Open Beta.
It allows you to adjust the pitch and playback rate of media files.

You must use a podcatcher that supports Presto.  The podcatcher will provide the interface
to control playback speed.  

Currently works for BeyondPod on my Galaxy S3 running CM 10.1.  No other app/device combinations have been tested.

## Download
[APK Download](https://github.com/TheRealFalcon/Prestissimo/blob/master/Prestissimo.apk) <br>
![QR code](https://raw.github.com/TheRealFalcon/Prestissimo/master/qrcode.png)

## Current limitations/issues
* Android 4.1+ (Jellybean) only
* Local files only (no streaming)
* If the calling activity crashes without properly stopping the stream, the stream will continue until Prestissimo is killed.
* Only support the Sonic speed adjustment library (no WSOLA).
* Preferences are hardcoded
* Error callback codes won't be descriptive or helpful
* InfoListenerCallback is never used
* Currently ignoring setVolume(...) call
* Currently ignoring sessionId
