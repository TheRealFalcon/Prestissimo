#Prestissimo

Prestissimo adds variable speed playback functionality to your audio applications.  It is a free drop-in replacement for the Presto Sound Library Open Beta.

You must use a podcatcher or audio player that supports Presto.  The podcatcher will provide the interface
to control playback speed.  

## Download
<a href="http://play.google.com/store/apps/details?id=com.falconware.prestissimo" alt="Download from Google Play">
  <img src="http://www.android.com/images/brand/android_app_on_play_large.png">
</a>


## Current limitations/issues
* Android 4.1+ (Jellybean) only
* Only support the Sonic speed adjustment library (no WSOLA).
* Preferences are hardcoded
* Error callback codes won't be descriptive or helpful

## Developers
If you're interested in contributing to Prestissimo, great! Some notes to help get you started: <br/>
Prestissimo is library used by other applications. Independent of an app using it, Prestissimo is of no value. The app that wishes to use Prestissimo simply has to include the Presto client jar (http://www.aocate.com/presto/), and import the Presto MediaPlayer instead of using the Android MediaPlayer (https://developer.android.com/reference/android/media/MediaPlayer.html). The Presto client reimplements most of the Android MediaPlayer APIs so that the implementing developer doesn't have to make many changes to accomodate the new library (in actuality, the Presto client is starting to show its age and hasn't kept up with changes to the Android APIs).

Prestissimo declares an intent filter in its manifest file, "com.aocate.intent.PLAY_AUDIO_ADJUST_SPEED_0_8". When the presto client (as part of the implementing app) sees this intent, it uses Prestissimo's implemention of the methods defined via AIDL. If the intent is not found, it will fall back to the Android MediaPlayer APIs and simply ignore any API calls that are Presto specific. Essentially, if Prestissimo is there, use it. If not, use the offical Android APIs.

At its core, Prestissimo is a service that only exists when it is bound. When the last client unbinds, Prestissimo will shutdown. When the implementing application creates a new instance of the Presto MediaPlayer, under the covers the Presto client will create a new binding to the SoundService and call the startSession method. The majority of the rest of the bound methods are simply reimplementing the MediaPlayer APIs.

If you only wish to understand how the actual audio is processed, src/com/falconware/prestissimo/Track.java should be all you need. If you wish to really understand how Prestissimo, the Presto client, and the implementing app interact, you should first grok the Presto client source: <br/>
http://www.aocate.com/presto/client/

Once you understand that, it should be fairly straightforward. Other required Android knowledge...

Classes (https://developer.android.com/reference/classes.html):
* MediaPlayer
* AudioTrack
* MediaCodec
* MediaExtractor

Concepts:
* Bound Services: https://developer.android.com/guide/components/bound-services.html
* AIDL: https://developer.android.com/guide/components/aidl.html
* Intent filters: https://developer.android.com/guide/components/intents-filters.html#ifs
