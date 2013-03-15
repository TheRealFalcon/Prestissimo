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

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

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
	// TODO: Cleanup...most of the interface implementation should move into the
	// Track class
	// TODO: If we receive a remote exception thats handled in the Track class,
	// we'll never remove the
	// 'garbage' reference from our mTracks array. Shouldn't be a problem as
	// there'd have to be A LOT
	// of failures before this is ever really an issue...but should probably fix

	private SparseArray<Track> mTracks;
	private static int trackId = 0;

	private final static String TAG_SERVICE = "PrestissimoService";
	protected final static String TAG_API = "PrestissimoAPI";

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG_SERVICE, "Service created");
		mTracks = new SparseArray<Track>();
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.d(TAG_SERVICE, "Returning binder");
		return mBinder;
	}

	// private final Track.KillMe killer = new Track.KillMe() {
	// // Fire it off in another thread so the 'current' thread doesn't try to
	// // interrupt/join itself.
	// @Override
	// public void die(final int sessionId) {
	// Thread t = new Thread(new Runnable() {
	// @Override
	// public void run() {
	// Track track = mTracks.get(sessionId);
	// track.release();
	// mTracks.delete(sessionId);
	// }
	// });
	// t.setDaemon(true);
	// t.start();
	// }
	// };

	// Indicates client has crashed. Stop the track and release any resources
	// associated with it
	private void handleRemoteException(long lSessionId) {
		int sessionId = (int) lSessionId;
		Track track = mTracks.get(sessionId);
		track.release();
		mTracks.delete(sessionId);

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
			Track track = mTracks.get((int) sessionId);
			return track.mCurrentPitch;
		}

		@Override
		public int getCurrentPosition(long sessionId) {
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_ERROR:
				track.error();
				break;
			default:
				return track.getCurrentPosition();
			}
			return -1;
		}

		@Override
		public float getCurrentSpeedMultiplier(long sessionId) {
			Track track = mTracks.get((int) sessionId);
			return track.mCurrentSpeed;
		}

		@Override
		public int getDuration(long sessionId) {
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_INITIALIZED:
			case Track.STATE_IDLE:
			case Track.STATE_ERROR:
				track.error();
				break;
			default:
				return (int) (track.mDuration / 1000);
			}
			return -1;
		}

		@Override
		public float getMaxSpeedMultiplier(long sessionId) {
			return 2;
		}

		@Override
		public float getMinSpeedMultiplier(long sessionId) {
			return (float) 0.5;
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
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_ERROR:
				track.error();
				break;
			default:
				return track.mCurrentState == Track.STATE_STARTED;
			}
			return false;
		}

		@Override
		public void pause(long sessionId) {
			Log.d(TAG_API, "Session: " + sessionId + ". Pause called");
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_STARTED:
			case Track.STATE_PAUSED:
				track.pause();
				track.mCurrentState = Track.STATE_PAUSED;
				Log.d(TAG_API, "State changed to Track.STATE_PAUSED");
				break;
			default:
				track.error();
			}

		}

		@Override
		public void prepare(long sessionId) {
			Log.d(TAG_API, "Session: " + sessionId + ". Prepare called");
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_INITIALIZED:
			case Track.STATE_STOPPED:
				track.initStream();
				track.mCurrentState = Track.STATE_PREPARED;
				Log.d(TAG_API, "Session: " + sessionId
						+ ". State changed to Track.STATE_PREPARED");
				try {
					track.preparedCallback.onPrepared();
				} catch (RemoteException e) {
					e.printStackTrace();
					release(sessionId);
				}
				break;
			default:
				track.error();
			}

		}

		@Override
		public void prepareAsync(long sessionId) {
			// Not supported yet
			Track track = mTracks.get((int) sessionId);
			Log.d(TAG_API, "Session: " + sessionId
					+ ". PrepareAsync called but not supported!");
			track.error();
		}

		@Override
		public void registerOnBufferingUpdateCallback(long sessionId,
				IOnBufferingUpdateListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.bufferingUpdateCallback = cb;
		}

		@Override
		public void registerOnCompletionCallback(long sessionId,
				IOnCompletionListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.completionCallback = cb;
		}

		@Override
		public void registerOnErrorCallback(long sessionId,
				IOnErrorListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.errorCallback = cb;
		}

		@Override
		public void registerOnInfoCallback(long sessionId,
				IOnInfoListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.infoCallback = cb;
		}

		@Override
		public void registerOnPitchAdjustmentAvailableChangedCallback(
				long sessionId,
				IOnPitchAdjustmentAvailableChangedListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.pitchAdjustmentAvailableChangedCallback = cb;
		}

		@Override
		public void registerOnPreparedCallback(long sessionId,
				IOnPreparedListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.preparedCallback = cb;
		}

		@Override
		public void registerOnSeekCompleteCallback(long sessionId,
				IOnSeekCompleteListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.seekCompleteCallback = cb;
		}

		@Override
		public void registerOnSpeedAdjustmentAvailableChangedCallback(
				long sessionId,
				IOnSpeedAdjustmentAvailableChangedListenerCallback_0_8 cb) {
			Track track = mTracks.get((int) sessionId);
			track.speedAdjustmentAvailableChangedCallback = cb;
		}

		@Override
		public void release(long sessionId) {
			Log.d(TAG_API, "Session: " + sessionId + ". Release called");
			Track track = mTracks.get((int) sessionId);
			track.release();
			mTracks.delete((int) sessionId);
			Log.d(TAG_API, "Session: " + sessionId
					+ ". State changed to Track.STATE_END");
		}

		@Override
		public void reset(long sessionId) {
			Log.d(TAG_API, "Session: " + sessionId + ". Reset called");
			Track track = mTracks.get((int) sessionId);
			track.reset();
			track.mCurrentState = Track.STATE_IDLE;
			Log.d(TAG_API, "Session: " + sessionId
					+ ". State changed to Track.STATE_IDLE");
			Log.d(TAG_API, "Session: " + sessionId + ". End of reset");
		}

		@Override
		public void seekTo(long sessionId, final int msec) {
			Log.d(TAG_API, "Session: " + sessionId + ". SeekTo called");
			Track track = mTracks.get((int) sessionId);
			track.seekTo(msec);
			Log.d(TAG_API, "Session: " + sessionId + ". SeekTo done");

		}

		@Override
		public void setAudioStreamType(long sessionId, int streamtype) {

		}

		@Override
		public void setDataSourceString(long sessionId, String path) {
			Log.d(TAG_API, "Session: " + sessionId
					+ ". SetDataSourceString called");
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_IDLE:
				track.mPath = path;
				track.mCurrentState = Track.STATE_INITIALIZED;
				Log.d(TAG_API, "Session: " + sessionId
						+ ". Moving state to STATE_INITIALIZED");
				break;
			default:
				track.error();
			}

		}

		@Override
		public void setDataSourceUri(long sessionId, Uri uri) {
			Track track = mTracks.get((int) sessionId);
			Log.e(TAG_API,
					"Session: "
							+ sessionId
							+ ". Attempting to set DataSourceUri which is not supported!");
			track.error();
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
			Track track = mTracks.get((int) sessionId);
			track.mCurrentPitch = f;
		}

		@Override
		public void setPlaybackSpeed(long sessionId, float f) {
			Track track = mTracks.get((int) sessionId);
			track.mCurrentSpeed = f;
		}

		@Override
		public void setSpeedAdjustmentAlgorithm(long sessionId, int algorithm) {

		}

		@Override
		public void setVolume(long sessionId, float left, float right) {
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_ERROR:
				track.error();
				break;
			default:
				// No idea how this should work... :)
			}
		}

		@Override
		public void start(long sessionId) {
			Log.d(TAG_API, "Session: " + sessionId + ". Start called");
			Track track = mTracks.get((int) sessionId);
			track.start();
			Log.d(TAG_API, "Session: " + sessionId + ". Start done");
		}

		@Override
		public long startSession(IDeathCallback_0_8 cb) {
			Log.d(TAG_API, "Calling startSession");
			final int sessionId = trackId++;
			Log.d(TAG_API, "Registering new sessionId: " + sessionId);
			try {
				cb.asBinder().linkToDeath(new DeathRecipient() {
					@Override
					public void binderDied() {
						Log.d(TAG_API, "Our caller is DEAD.  Releasing.");
						handleRemoteException(sessionId);
						return;
					}
				}, 0);
			} catch (RemoteException e) {
				e.printStackTrace();
				Log.wtf(TAG_API,
						"Service died when trying to set what to do when it dies.  Good luck!");
			}
			// It seems really strange to me to passing this callback to the
			// track since it never actually gets called or used by the track.
			// However, cb will be a candidate for garbage collection after this
			// method pops unless we 'do' something with it.
			mTracks.append(sessionId, new Track(cb));
			return sessionId;
		}

		@Override
		public void stop(long sessionId) {
			Log.d(TAG_API, "Session: " + sessionId + ". Stop called");
			Track track = mTracks.get((int) sessionId);
			switch (track.mCurrentState) {
			case Track.STATE_PREPARED:
			case Track.STATE_STARTED:
			case Track.STATE_STOPPED:
			case Track.STATE_PAUSED:
			case Track.STATE_PLAYBACK_COMPLETED:
				track.mCurrentState = Track.STATE_STOPPED;
				Log.d(TAG_API, "State changed to Track.STATE_STOPPED");
				track.stop();
				Log.d(TAG_API, "Session: " + sessionId + ". Stop done");
				break;

			default:
				track.error();
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

	};
}
