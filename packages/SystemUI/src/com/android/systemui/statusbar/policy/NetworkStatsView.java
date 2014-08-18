/*
 * Copyright (C) 2013 The ChameleonOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.R;

import java.lang.Math;

public class NetworkStatsView extends LinearLayout {

    private Handler mHandler;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mActivated;     // whether or not activated due to system settings
    private boolean mScreenOn;
    private boolean mNetworkAvailable;
    private boolean mShowArrow;

    private TextView mTextViewTx;
    private TextView mTextViewRx;
    private ImageView mImageViewTx;
    private ImageView mImageViewRx;
    private long mLastTx;
    private long mLastRx;
    private long mRefreshInterval;
    private long mLastUpdateTime;
    private Context mContext;
    protected int mNetStatsColor;

    SettingsObserver mSettingsObserver;

    public NetworkStatsView(Context context) {
        this(context, null);
    }

    public NetworkStatsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkStatsView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mLastRx = TrafficStats.getTotalRxBytes();
        mLastTx = TrafficStats.getTotalTxBytes();
        mHandler = new Handler();
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    // runnable to invalidate view via mHandler.postDelayed() call
    private final Runnable mUpdateRunnable = new Runnable() {
        public void run() {
            if (mActivated && mAttached) {
                updateStats();
                invalidate();
            }
        }
    };

    // observes changes in system settings and enables/disables view accordingly
    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_STATS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_STATS_SHOW_ARROW), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_STATS_UPDATE_INTERVAL), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_STATS_TEXT_COLOR), false, this);
            onChange(true);
        }

        public void unobserver() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mActivated = (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_NETWORK_STATS, 0)) == 1;

            mRefreshInterval = Settings.System.getLong(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_NETWORK_STATS_UPDATE_INTERVAL, 500);

            mShowArrow = (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_NETWORK_STATS_SHOW_ARROW, 1)) == 1;

            if (mShowArrow) {
                mImageViewRx.setVisibility(View.VISIBLE);
                mImageViewTx.setVisibility(View.VISIBLE);
            } else {
                mImageViewRx.setVisibility(View.GONE);
                mImageViewTx.setVisibility(View.GONE);
            }

            int newColor = 0;
            ContentResolver resolver = getContext().getContentResolver();
            newColor = Settings.System.getInt(resolver,
                    Settings.System.STATUS_BAR_NETWORK_STATS_TEXT_COLOR, mNetStatsColor);
            if (newColor < 0 && newColor != mNetStatsColor) {
                mNetStatsColor = newColor;
                if (mTextViewTx != null) mTextViewTx.setTextColor(mNetStatsColor);
                if (mTextViewRx != null) mTextViewRx.setTextColor(mNetStatsColor);
            }

            mNetworkAvailable = isNetworkAvailable();
            if (mActivated && mAttached && mNetworkAvailable) {
                setVisibility(View.VISIBLE);
                updateStats();
            } else {
                setVisibility(View.GONE);
            }
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                mScreenOn = true;
                if (mActivated && mAttached && mNetworkAvailable) {
                    setVisibility(View.VISIBLE);
                    updateStats();
                }
            }
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                if (mActivated && mAttached) {
                    setVisibility(View.GONE);
                }
            }
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                if (mActivated && mAttached && mScreenOn) {
                    mNetworkAvailable = isNetworkAvailable();
                    if (mNetworkAvailable) {
                        setVisibility(View.VISIBLE);
                        updateStats();
                    } else {
                        setVisibility(View.GONE);
                    }
                }
            }
        }
    };

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTextViewTx = (TextView) findViewById(R.id.bytes_tx);
        mTextViewRx = (TextView) findViewById(R.id.bytes_rx);
        mImageViewTx = (ImageView) findViewById(R.id.img_tx);
        mImageViewRx = (ImageView) findViewById(R.id.img_rx);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mNetStatsColor = mTextViewTx.getTextColors().getDefaultColor();
            mScreenOn = isScreenOn();
        }

        // register the broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        // start observing our settings
        mSettingsObserver.observe();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
        }

        // unregister the broadcast receiver
        mContext.unregisterReceiver(mBroadcastReceiver);

        // stop listening for settings changes
        mSettingsObserver.unobserver();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(
                                Context.CONNECTIVITY_SERVICE);
        final NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null ? activeNetwork.isConnected() : false;
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        return pm.isScreenOn();
    }

    private void updateStats() {
        if (!mActivated || !mAttached || !mScreenOn || !mNetworkAvailable) {
            mHandler.removeCallbacks(mUpdateRunnable);
            return;
        }

        final long currentBytesTx = TrafficStats.getTotalTxBytes();
        final long currentBytesRx = TrafficStats.getTotalRxBytes();
        final long currentTimeMillis = System.currentTimeMillis();
        long deltaBytesTx = currentBytesTx - mLastTx;
        long deltaBytesRx = currentBytesRx - mLastRx;
        mLastTx = currentBytesTx;
        mLastRx = currentBytesRx;

        if (deltaBytesRx < 0)
            deltaBytesRx = 0;
        if (deltaBytesTx < 0)
            deltaBytesTx = 0;

        final float deltaT = (currentTimeMillis - mLastUpdateTime) / 1000f;
        mLastUpdateTime = currentTimeMillis;
        setTextViewSpeed(mTextViewTx, deltaBytesTx, deltaT);
        setTextViewSpeed(mTextViewRx, deltaBytesRx, deltaT);

        mHandler.removeCallbacks(mUpdateRunnable);
        mHandler.postDelayed(mUpdateRunnable, mRefreshInterval);
    }

    private void setTextViewSpeed(TextView tv, long speed, float deltaT) {
        long lSpeed = Math.round(speed / deltaT);
        if (lSpeed > 900) {
            tv.setText(Formatter.formatFileSize(mContext, lSpeed));
        } else {
            tv.setText("");
        }
    }
}
