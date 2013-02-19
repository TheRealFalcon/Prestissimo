//Copyright 2012 James Falcon
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.

package com.falconware.prestissimo;

import java.nio.ByteBuffer;

import org.vinuxproject.sonic.Sonic;

import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
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
import com.aocate.presto.service.IPlayMedia_0_8;

public class SoundService extends Service {
	private AudioTrack mTrack;
	private Sonic mSonic;
	private MediaExtractor mExtractor;
	private MediaFormat mFormat;
	private MediaCodec mCodec;
	private String mPath;
	private int mCurrentState;
	private static boolean startSessionCalled = false;
	private Thread mDecoderThread;

	private long mDuration;
	private float mCurrentSpeed;
	private float mCurrentPitch;
	private final static int TRACK_NUM = 0;
	// Currently only supporting one instance
	private final static int GLOBAL_SESSION_ID = 1;

	private final static int STATE_IDLE = 0;
	private final static int STATE_INITIALIZED = 1;
	private final static int STATE_PREPARING = 2;
	private final static int STATE_PREPARED = 3;
	private final static int STATE_STARTED = 4;
	private final static int STATE_PAUSED = 5;
	private final static int STATE_STOPPED = 6;
	private final static int STATE_PLAYBACK_COMPLETED = 7;
	private final static int STATE_END = 8;
	private final static int STATE_ERROR = 9;

	private IOnErrorListenerCallback_0_8 errorCallback;
	private IOnCompletionListenerCallback_0_8 completionCallback;
	private IOnBufferingUpdateListenerCallback_0_8 bufferingUpdateCallback;
	private IOnInfoListenerCallback_0_8 infoCallback;
	private IOnPitchAdjustmentAvailableChangedListenerCallback_0_8 pitchAdjustmentAvailableChangedCallback;
	private IOnPreparedListenerCallback_0_8 preparedCallback;
	private IOnSeekCompleteListenerCallback_0_8 seekCompleteCallback;
	private IOnSpeedAdjustmentAvailableChangedListenerCallback_0_8 speedAdjustmentAvailableChangedCallback;

	@Override
	public void onCreate() {
		super.onCreate();
		mCurrentState = STATE_IDLE;
		mCurrentSpeed = (float) 1.0;
		mCurrentPitch = (float) 1.0;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
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

	private void initStream() {
		mExtractor = new MediaExtractor();
		mExtractor.setDataSource(mPath);
		mFormat = mExtractor.getTrackFormat(TRACK_NUM);

		int sampleRate = mFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
		int channelCount = mFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
		mDuration = mFormat.getLong(MediaFormat.KEY_DURATION);

		Log.d("initStream", "Sample rate: " + sampleRate);
		initDevice(sampleRate, channelCount);

		mExtractor.selectTrack(TRACK_NUM);
		String mime = mFormat.getString(MediaFormat.KEY_MIME);
		Log.d("decode", "Mime type: " + mime);
		mCodec = MediaCodec.createDecoderByType(mime);

		mCodec.configure(mFormat, null, null, 0);
	}

	private void decode() {
		mDecoderThread = new Thread(new Runnable() {
			@Override
			public void run() {
				mCodec.start();

				ByteBuffer[] inputBuffers = mCodec.getInputBuffers();
				ByteBuffer[] outputBuffers = mCodec.getOutputBuffers();

				boolean sawInputEOS = false;
				boolean sawOutputEOS = false;

				while (!sawInputEOS
						&& !sawOutputEOS
						&& (mCurrentState == STATE_STARTED || mCurrentState == STATE_PAUSED)) {
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
				mCodec.stop();
				mTrack.stop();
				if (sawInputEOS && sawOutputEOS) {
					mCurrentState = STATE_PLAYBACK_COMPLETED;
					if (completionCallback != null) {
						try {
							completionCallback.onCompletion();
						} catch (RemoteException e) {
							e.printStackTrace();
							try {
								// I could keep doing this for a LOOOOOONG time
								mBinder.release(GLOBAL_SESSION_ID);
							} catch (RemoteException ee) {
								ee.printStackTrace();
							}
						}
					}
				}
			}
		});
		mDecoderThread.setDaemon(true);
		mDecoderThread.start();
	}

	private final IPlayMedia_0_8.Stub mBinder = new IPlayMedia_0_8.Stub() {

		@Override
		public boolean canSetPitch(long sessionId) {
			return true;
		}

		@Override
		public boolean canSetSpeed(long sessionId) {
			return true;
		}

		@Override
		public float getCurrentPitchStepsAdjustment(long sessionId) {
			return mCurrentPitch;
		}

		@Override
		public int getCurrentPosition(long sessionId) {
			switch (mCurrentState) {
			case STATE_ERROR:
				error();
				break;
			default:
				return (int) (mExtractor.getSampleTime() / 1000);
			}
			return -1;
		}

		@Override
		public float getCurrentSpeedMultiplier(long sessionId) {
			return mCurrentSpeed;
		}

		@Override
		public int getDuration(long sessionId) {
			switch (mCurrentState) {
			case STATE_INITIALIZED:
			case STATE_IDLE:
			case STATE_ERROR:
				error();
				break;
			default:
				return (int) (mDuration / 1000);
			}
			return -1;
		}

		@Override
		public float getMaxSpeedMultiplier(long sessionId) {
			return 2;
		}

		@Override
		public float getMinSpeedMultiplier(long sessionId) {
			return (float) 0.6;
		}

		@Override
		public int getVersionCode() {
			return 1;
		}

		@Override
		public String getVersionName() {
			return "0.1";
		}

		@Override
		public boolean isLooping(long sessionId) {
			// No
			return false;
		}

		@Override
		public boolean isPlaying(long sessionId) {
			switch (mCurrentState) {
			case STATE_ERROR:
				error();
				break;
			default:
				return mCurrentState == STATE_STARTED;
			}
			return false;
		}

		@Override
		public void pause(long sessionId) {
			switch (mCurrentState) {
			case STATE_STARTED:
			case STATE_PAUSED:
				mTrack.pause();
				mCurrentState = STATE_PAUSED;
				break;
			default:
				error();
			}

		}

		@Override
		public void prepare(long sessionId) {
			switch (mCurrentState) {
			case STATE_INITIALIZED:
			case STATE_STOPPED:
				initStream();
				mCurrentState = STATE_PREPARED;
				try {
					preparedCallback.onPrepared();
				} catch (RemoteException e) {
					e.printStackTrace();
					release(GLOBAL_SESSION_ID);
				}
				break;
			default:
				error();
			}

		}

		@Override
		public void prepareAsync(long sessionId) {
			// Not supported yet
			error();
		}

		@Override
		public void registerOnBufferingUpdateCallback(long sessionId,
				IOnBufferingUpdateListenerCallback_0_8 cb) {
			bufferingUpdateCallback = cb;
		}

		@Override
		public void registerOnCompletionCallback(long sessionId,
				IOnCompletionListenerCallback_0_8 cb) {
			completionCallback = cb;
		}

		@Override
		public void registerOnErrorCallback(long sessionId,
				IOnErrorListenerCallback_0_8 cb) {
			errorCallback = cb;
		}

		@Override
		public void registerOnInfoCallback(long sessionId,
				IOnInfoListenerCallback_0_8 cb) {
			infoCallback = cb;
		}

		@Override
		public void registerOnPitchAdjustmentAvailableChangedCallback(
				long sessionId,
				IOnPitchAdjustmentAvailableChangedListenerCallback_0_8 cb) {
			pitchAdjustmentAvailableChangedCallback = cb;
		}

		@Override
		public void registerOnPreparedCallback(long sessionId,
				IOnPreparedListenerCallback_0_8 cb) {
			preparedCallback = cb;
		}

		@Override
		public void registerOnSeekCompleteCallback(long sessionId,
				IOnSeekCompleteListenerCallback_0_8 cb) {
			seekCompleteCallback = cb;
		}

		@Override
		public void registerOnSpeedAdjustmentAvailableChangedCallback(
				long sessionId,
				IOnSpeedAdjustmentAvailableChangedListenerCallback_0_8 cb) {
			speedAdjustmentAvailableChangedCallback = cb;
		}

		@Override
		public void release(long sessionId) {
			reset(sessionId);
			errorCallback = null;
			completionCallback = null;
			bufferingUpdateCallback = null;
			infoCallback = null;
			pitchAdjustmentAvailableChangedCallback = null;
			preparedCallback = null;
			seekCompleteCallback = null;
			speedAdjustmentAvailableChangedCallback = null;
			mCurrentState = STATE_END;
			// Goodbye cruel world
		}

		@Override
		public void reset(long sessionId) {
			mCurrentState = STATE_IDLE;
			if (mDecoderThread != null) {
				try {
					mDecoderThread.interrupt();
					mDecoderThread.join();
				} catch (InterruptedException e) {
					// Bail and go to error state
					e.printStackTrace();
					error();
					return;
				}
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

		@Override
		public void seekTo(long sessionId, final int msec) {
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
							e.printStackTrace();
							release(GLOBAL_SESSION_ID);
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

		@Override
		public void setAudioStreamType(long sessionId, int streamtype) {

		}

		@Override
		public void setDataSourceString(long sessionId, String path) {
			switch (mCurrentState) {
			case STATE_IDLE:
				mPath = path;
				mCurrentState = STATE_INITIALIZED;
				break;
			default:
				error();
			}

		}

		@Override
		public void setDataSourceUri(long sessionId, Uri uri) {
			System.out
					.println("Attempting to set DataSourceUri which is not supported!");
			error();
		}

		@Override
		public void setEnableSpeedAdjustment(long sessionId,
				boolean enableSpeedAdjustment) {

		}

		@Override
		public void setLooping(long sessionId, boolean looping) {

		}

		@Override
		public void setPitchStepsAdjustment(long sessionId, float pitchSteps) {

		}

		@Override
		public void setPlaybackPitch(long sessionId, float f) {
			mCurrentPitch = f;
		}

		@Override
		public void setPlaybackSpeed(long sessionId, float f) {
			mCurrentSpeed = f;
		}

		@Override
		public void setSpeedAdjustmentAlgorithm(long sessionId, int algorithm) {

		}

		@Override
		public void setVolume(long sessionId, float left, float right) {
			switch (mCurrentState) {
			case STATE_ERROR:
				error();
				break;
			default:
				// No idea how this should work... :)
			}
		}

		@Override
		public void start(long sessionId) {
			switch (mCurrentState) {
			case STATE_PREPARED:
			case STATE_PLAYBACK_COMPLETED:
				mCurrentState = STATE_STARTED;
				decode();
				mTrack.play();
			case STATE_STARTED:
				break;
			case STATE_PAUSED:
				mCurrentState = STATE_STARTED;
				mDecoderThread.interrupt();
				mTrack.play();
				break;
			default:
				mCurrentState = STATE_ERROR;
				if (mTrack != null) {
					error();
				} else {
					Log.d("start",
							"Attempting to start while in idle after construction.  Not allowed by no callbacks called");
				}
			}
		}

		@Override
		public long startSession(IDeathCallback_0_8 cb) {
			if (startSessionCalled) {
				Log.e("startSession",
						"Currently only single instance.  Don't call more than once");
				// error();
			}
			startSessionCalled = true;
			return GLOBAL_SESSION_ID;
		}

		@Override
		public void stop(long sessionId) {
			switch (mCurrentState) {
			case STATE_PREPARED:
			case STATE_STARTED:
			case STATE_STOPPED:
			case STATE_PAUSED:
			case STATE_PLAYBACK_COMPLETED:
				mCurrentState = STATE_STOPPED;
				mTrack.pause();
				mTrack.flush();
				break;

			default:
				error();
			}
		}

		// wtf are these unregister methods for???
		@Override
		public void unregisterOnBufferingUpdateCallback(long sessionId,
				IOnBufferingUpdateListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnBufferingUpdateCallback. This should never happen!");
		}

		@Override
		public void unregisterOnCompletionCallback(long sessionId,
				IOnCompletionListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnCompletionCallback. This should never happen!");
		}

		@Override
		public void unregisterOnErrorCallback(long sessionId,
				IOnErrorListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnErrorCallback. This should never happen!");
		}

		@Override
		public void unregisterOnInfoCallback(long sessionId,
				IOnInfoListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnInfoCallback. This should never happen!");
		}

		@Override
		public void unregisterOnPitchAdjustmentAvailableChangedCallback(
				long sessionId,
				IOnPitchAdjustmentAvailableChangedListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnPitchAdjustmentAvailableChangedCallback. This should never happen!");
		}

		@Override
		public void unregisterOnPreparedCallback(long sessionId,
				IOnPreparedListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnPreparedCallback. This should never happen!");
		}

		@Override
		public void unregisterOnSeekCompleteCallback(long sessionId,
				IOnSeekCompleteListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnSeekCompleteCallback. This should never happen!");
		}

		@Override
		public void unregisterOnSpeedAdjustmentAvailableChangedCallback(
				long sessionId,
				IOnSpeedAdjustmentAvailableChangedListenerCallback_0_8 cb) {
			Log.e("SoundService",
					"In unregisterOnSpeedAdjustmentAvailableChangedCallback. This should never happen!");
		}

		private void error() {
			mCurrentState = STATE_ERROR;
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
				// Print stack trace and end your woeful life
				e.printStackTrace();
				release(GLOBAL_SESSION_ID);
			}
		}

	};
}
