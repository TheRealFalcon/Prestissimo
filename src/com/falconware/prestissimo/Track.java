package com.falconware.prestissimo;

import java.nio.ByteBuffer;

import org.vinuxproject.sonic.Sonic;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.RemoteException;
import android.util.Log;

import com.aocate.presto.service.IDeathCallback_0_8;
import com.aocate.presto.service.IOnBufferingUpdateListenerCallback_0_8;
import com.aocate.presto.service.IOnCompletionListenerCallback_0_8;
import com.aocate.presto.service.IOnErrorListenerCallback_0_8;
import com.aocate.presto.service.IOnInfoListenerCallback_0_8;
import com.aocate.presto.service.IOnPitchAdjustmentAvailableChangedListenerCallback_0_8;
import com.aocate.presto.service.IOnPreparedListenerCallback_0_8;
import com.aocate.presto.service.IOnSeekCompleteListenerCallback_0_8;
import com.aocate.presto.service.IOnSpeedAdjustmentAvailableChangedListenerCallback_0_8;

public class Track {
	private AudioTrack mTrack;
	private Sonic mSonic;
	private MediaExtractor mExtractor;
	private MediaFormat mFormat;
	private MediaCodec mCodec;
	private Thread mDecoderThread;
	private boolean mContinue;
	private final static int TRACK_NUM = 0;
	private final static String TAG_TRACK = "PrestissimoTrack";

	protected long mDuration;
	protected float mCurrentSpeed;
	protected float mCurrentPitch;
	protected int mCurrentState;
	protected String mPath;

	protected IOnErrorListenerCallback_0_8 errorCallback;
	protected IOnCompletionListenerCallback_0_8 completionCallback;
	protected IOnBufferingUpdateListenerCallback_0_8 bufferingUpdateCallback;
	protected IOnInfoListenerCallback_0_8 infoCallback;
	protected IOnPitchAdjustmentAvailableChangedListenerCallback_0_8 pitchAdjustmentAvailableChangedCallback;
	protected IOnPreparedListenerCallback_0_8 preparedCallback;
	protected IOnSeekCompleteListenerCallback_0_8 seekCompleteCallback;
	protected IOnSpeedAdjustmentAvailableChangedListenerCallback_0_8 speedAdjustmentAvailableChangedCallback;

	public final static int STATE_IDLE = 0;
	public final static int STATE_INITIALIZED = 1;
	public final static int STATE_PREPARING = 2;
	public final static int STATE_PREPARED = 3;
	public final static int STATE_STARTED = 4;
	public final static int STATE_PAUSED = 5;
	public final static int STATE_STOPPED = 6;
	public final static int STATE_PLAYBACK_COMPLETED = 7;
	public final static int STATE_END = 8;
	public final static int STATE_ERROR = 9;

	// Don't know how to persist this other than pass it in and 'hold' it
	private final IDeathCallback_0_8 mDeath;

	public Track(IDeathCallback_0_8 cb) {
		mCurrentState = STATE_IDLE;
		mCurrentSpeed = (float) 1.0;
		mCurrentPitch = (float) 1.0;
		mContinue = false;
		mDeath = cb;
	}

	public int getCurrentPosition() {
		return (int) (mExtractor.getSampleTime() / 1000);
	}

	public void pause() {
		mTrack.pause();
	}

	public void stop() {
		mContinue = false;
		mTrack.pause();
		mTrack.flush();
	}

	public void start() {
		switch (mCurrentState) {
		case Track.STATE_PREPARED:
		case Track.STATE_PLAYBACK_COMPLETED:
			mCurrentState = Track.STATE_STARTED;
			Log.d(SoundService.TAG_API, "State changed to Track.STATE_STARTED");
			mContinue = true;
			decode();
			mTrack.play();
		case Track.STATE_STARTED:
			break;
		case Track.STATE_PAUSED:
			mCurrentState = Track.STATE_STARTED;
			Log.d(SoundService.TAG_API, "State changed to Track.STATE_PAUSED");
			mDecoderThread.interrupt();
			mTrack.play();
			break;
		default:
			mCurrentState = Track.STATE_ERROR;
			Log.d(SoundService.TAG_API,
					"State changed to Track.STATE_ERROR in start");
			if (mTrack != null) {
				error();
			} else {
				Log.d("start",
						"Attempting to start while in idle after construction.  Not allowed by no callbacks called");
			}
		}

	}

	public void release() {
		reset();
		errorCallback = null;
		completionCallback = null;
		bufferingUpdateCallback = null;
		infoCallback = null;
		pitchAdjustmentAvailableChangedCallback = null;
		preparedCallback = null;
		seekCompleteCallback = null;
		speedAdjustmentAvailableChangedCallback = null;
		mCurrentState = Track.STATE_END;
	}

	public void initStream() {
		mExtractor = new MediaExtractor();
		mExtractor.setDataSource(mPath);
		mFormat = mExtractor.getTrackFormat(TRACK_NUM);

		int sampleRate = mFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
		int channelCount = mFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
		mDuration = mFormat.getLong(MediaFormat.KEY_DURATION);

		Log.v(TAG_TRACK, "Sample rate: " + sampleRate);
		initDevice(sampleRate, channelCount);

		mExtractor.selectTrack(TRACK_NUM);
		String mime = mFormat.getString(MediaFormat.KEY_MIME);
		Log.v(TAG_TRACK, "Mime type: " + mime);
		mCodec = MediaCodec.createDecoderByType(mime);

		mCodec.configure(mFormat, null, null, 0);
	}

	public void reset() {
		mContinue = false;
		try {
			if (mDecoderThread != null) {
				mDecoderThread.interrupt();
				mDecoderThread.join();
			}
		} catch (InterruptedException e) {
			// WTF is happening?
			e.printStackTrace();
		}
		if (mCodec != null) {
			mCodec.release();
			mCodec = null;
		}
		if (mExtractor != null) {
			mExtractor.release();
			mExtractor = null;
		}
		if (mTrack != null) {
			mTrack.release();
			mTrack = null;
		}
		mFormat = null;

	}

	public void seekTo(final int msec) {
		switch (mCurrentState) {
		case STATE_PREPARED:
		case STATE_STARTED:
		case STATE_PAUSED:
		case STATE_PLAYBACK_COMPLETED:
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					mTrack.flush();
					mExtractor.seekTo(((long) msec * 1000),
							MediaExtractor.SEEK_TO_CLOSEST_SYNC);
					try {
						seekCompleteCallback.onSeekComplete();
					} catch (RemoteException e) {
						// Binder should handle our death
					}
				}
			});
			t.setDaemon(true);
			t.start();
			break;
		default:
			error();
		}
	}

	public void error() {
		Log.e(TAG_TRACK, "Moved to error state!");
		mCurrentState = Track.STATE_ERROR;
		try {
			if (errorCallback != null) {
				boolean handled = errorCallback.onError(
						MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
				if (!handled) {
					completionCallback.onCompletion();
				}
			} else {
				completionCallback.onCompletion();
			}
		} catch (RemoteException e) {
			// Binder should handle our death
		}
	}

	private int findFormatFromChannels(int numChannels) {
		switch (numChannels) {
		case 1:
			return AudioFormat.CHANNEL_OUT_MONO;
		case 2:
			return AudioFormat.CHANNEL_OUT_STEREO;
		default:
			return -1; // Error
		}
	}

	private void initDevice(int sampleRate, int numChannels) {
		int format = findFormatFromChannels(numChannels);
		int minSize = AudioTrack.getMinBufferSize(sampleRate, format,
				AudioFormat.ENCODING_PCM_16BIT);
		mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, format,
				AudioFormat.ENCODING_PCM_16BIT, minSize * 4,
				AudioTrack.MODE_STREAM);
		mSonic = new Sonic(sampleRate, numChannels);
	}

	public void decode() {
		mDecoderThread = new Thread(new Runnable() {
			@Override
			public void run() {
				mCodec.start();

				ByteBuffer[] inputBuffers = mCodec.getInputBuffers();
				ByteBuffer[] outputBuffers = mCodec.getOutputBuffers();

				boolean sawInputEOS = false;
				boolean sawOutputEOS = false;

				while (!sawInputEOS && !sawOutputEOS && mContinue) {
					if (mCurrentState == STATE_PAUSED) {
						try {
							Thread.sleep(99999999);
						} catch (InterruptedException e) {
							// Purposely not doing anything here
						}
						continue;
					}
					mSonic.setSpeed(mCurrentSpeed);
					mSonic.setPitch(mCurrentPitch);
					int inputBufIndex = mCodec.dequeueInputBuffer(-1);
					if (inputBufIndex >= 0) {
						ByteBuffer dstBuf = inputBuffers[inputBufIndex];
						int sampleSize = mExtractor.readSampleData(dstBuf, 0);
						long presentationTimeUs = 0;
						if (sampleSize < 0) {
							sawInputEOS = true;
							sampleSize = 0;
						} else {
							presentationTimeUs = mExtractor.getSampleTime();
						}
						mCodec.queueInputBuffer(
								inputBufIndex,
								0,
								sampleSize,
								presentationTimeUs,
								sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM
										: 0);
						if (!sawInputEOS) {
							mExtractor.advance();
						}
					}

					MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
					int res;
					do {
						res = mCodec.dequeueOutputBuffer(info, -1);
						if (res >= 0) {
							int outputBufIndex = res;
							ByteBuffer buf = outputBuffers[outputBufIndex];

							final byte[] chunk = new byte[info.size];
							buf.get(chunk);
							buf.clear();

							if (chunk.length > 0) {
								mSonic.putBytes(chunk, chunk.length);
								int available = mSonic.availableBytes();
								if (available > 0) {
									final byte[] modifiedSamples = new byte[available];
									mSonic.receiveBytes(modifiedSamples,
											available);
									mTrack.write(modifiedSamples, 0, available);
								}
							} else {
								mSonic.flush();
							}
							mCodec.releaseOutputBuffer(outputBufIndex, false);

							if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
								sawOutputEOS = true;
							}
						} else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
							outputBuffers = mCodec.getOutputBuffers();
							Log.d("PCM", "Output buffers changed");
						} else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
							final MediaFormat oformat = mCodec
									.getOutputFormat();
							Log.d("PCM", "Output format has changed to"
									+ oformat);
							outputBuffers = mCodec.getOutputBuffers();
						}
					} while (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
							|| res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
				}
				Log.d(TAG_TRACK,
						"Decoding loop exited. Stopping codec and track");
				Log.d(TAG_TRACK, "Duration: " + (int) (mDuration / 1000));
				Log.d(TAG_TRACK,
						"Current position: "
								+ (int) (mExtractor.getSampleTime() / 1000));
				mCodec.stop();
				mTrack.stop();
				Log.d(TAG_TRACK, "Stopped codec and track");
				Log.d(TAG_TRACK,
						"Current position: "
								+ (int) (mExtractor.getSampleTime() / 1000));
				if (sawInputEOS && sawOutputEOS) {
					mCurrentState = STATE_PLAYBACK_COMPLETED;
					if (completionCallback != null) {
						try {
							completionCallback.onCompletion();
						} catch (RemoteException e) {
							// Binder should handle our death
						}
					}
				} else {
					Log.d(TAG_TRACK,
							"Loop ended before saw input eos or output eos");
					Log.d(TAG_TRACK, "sawInputEOS: " + sawInputEOS);
					Log.d(TAG_TRACK, "sawOutputEOS: " + sawOutputEOS);
				}
			}
		});
		mDecoderThread.setDaemon(true);
		mDecoderThread.start();
	}

	public interface KillMe {
		void die(int sessionId);
	}
}
