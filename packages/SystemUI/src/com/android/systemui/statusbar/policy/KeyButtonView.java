/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.ThemeConfig;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.HapticFeedbackConstants;
import android.view.HardwareCanvas;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RenderNodeAnimator;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import java.io.File;
import java.lang.Math;
import java.util.ArrayList;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.cmremix.CMRemixActions;
import com.android.internal.util.cmremix.KeyButtonInfo;
import com.android.internal.util.cmremix.NavbarUtils;
import com.android.internal.util.cmremix.NavbarConstants;
import com.android.systemui.R;

import com.android.internal.util.cmremix.DeviceUtils;

import static com.android.internal.util.cmremix.NavbarConstants.*;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;

public class KeyButtonView extends ImageView {
    private static final String TAG = "StatusBar.KeyButtonView";
    private static final boolean DEBUG = NavbarUtils.DEBUG;

    public static final float DEFAULT_QUIESCENT_ALPHA = 1f;
    public static final float DEFAULT_LAYOUT_CHANGER_ALPHA = 0.20f;
    private static final int DPAD_TIMEOUT_INTERVAL = 500;
    private static final int DPAD_REPEAT_INTERVAL = 75;

    private int mLongPressTimeout;
    private int mCode = 4;  // AOSP uses 4 for the back key

    private long mDownTime;
    private long mUpTime;
    int mTouchSlop;
    private float mDrawingAlpha = 1f;
    private float mQuiescentAlpha = DEFAULT_QUIESCENT_ALPHA;
    private Animator mAnimateToQuiescent = new ObjectAnimator();
    boolean mShouldClick = true;

    private static AudioManager mAudioManager;
    static PowerManager mPm;

    private KeyButtonRipple mRipple;
    KeyButtonInfo mActions;

    public boolean mHasBlankSingleAction = false, mHasDoubleAction, mHasLongAction;
    boolean mIsDPadAction = false;
    boolean mHasSingleAction = false;
    private boolean mIsLandscape = false;

    private boolean mShouldTintIcons = true;

    private boolean mTablet = false;

    public static PowerManager getPowerManagerService(Context context) {
        if (mPm == null) mPm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return mPm;
    }

    public static AudioManager getAudioManagerService(Context context) {
        if (mAudioManager == null) mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return mAudioManager;
    }

    Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                removeCallbacks(mSingleTap);
                doLongPress();
            }
        }
    };

    Runnable mSingleTap = new Runnable() {
        @Override
        public void run() {
            if (!isPressed()) {
                doSinglePress();
            }
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        setClickable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        setLongClickable(false);
        mAudioManager = getAudioManagerService(context);
        mPm = getPowerManagerService(context);
        setBackground(mRipple = new KeyButtonRipple(context, this));
        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();
    }

    public void setDeviceOrientation(boolean landscape, boolean tablet) {
        mIsLandscape = landscape;
        mTablet = tablet;
    }

    public void setButtonActions(KeyButtonInfo actions) {
        this.mActions = actions;

        if (mActions != null) {
            mHasSingleAction = (mActions.singleAction != null);
            mHasLongAction = mActions.longPressAction != null;
            mHasDoubleAction = mActions.doubleTapAction != null;
            mHasBlankSingleAction = mHasSingleAction && mActions.singleAction.equals(ACTION_BLANK);

            mIsDPadAction = mHasSingleAction
                && (mActions.singleAction.equals(ACTION_ARROW_LEFT)
                || mActions.singleAction.equals(ACTION_ARROW_UP)
                || mActions.singleAction.equals(ACTION_ARROW_DOWN)
                || mActions.singleAction.equals(ACTION_ARROW_RIGHT));

            setImage();
            setTag(mActions.singleAction);
            setLongClickable(mHasLongAction);

            if (DEBUG) Log.d(TAG, "Adding a navbar button in landscape or portrait " + mActions.singleAction);
        } else {
            Log.e(TAG, "hmmm. mActions was null...");
        }
    }

    public void setLongPressTimeout(int lpTimeout) {
        mLongPressTimeout = lpTimeout;
    }

    /* @hide */
    public void setImage() {
        setImage(getResources());
    }

    /* @hide */
    public void setImage(final Resources res) {
        if (mActions.iconUri != null && mActions.iconUri.length() > 0) {
            File f = new File(Uri.parse(mActions.iconUri).getPath());
            if (f.exists()) {
                setImageDrawable(new BitmapDrawable(res, f.getAbsolutePath()));
            }
        } else if (mHasSingleAction) {
            if (mIsLandscape && !mTablet) {
                setImageDrawable(NavbarUtils.getLandscapeIconImage(mContext, mActions.singleAction));
            } else {
                setImageDrawable(NavbarUtils.getIconImage(mContext, mActions.singleAction));
            }
        } else {
            setImageResource(R.drawable.ic_sysbar_null);
        }
    }

    @Override
    public Resources getResources() {
        ThemeConfig themeConfig = mContext.getResources().getConfiguration().themeConfig;
        Resources res = null;
        if (themeConfig != null) {
            try {
                final String navbarThemePkgName = themeConfig.getOverlayForNavBar();
                final String sysuiThemePkgName = themeConfig.getOverlayForStatusBar();
                // Check if the same theme is applied for systemui, if so we can skip this
                if (navbarThemePkgName != null && !navbarThemePkgName.equals(sysuiThemePkgName)) {
                    res = mContext.getPackageManager().getThemedResourcesForApplication(
                            mContext.getPackageName(), navbarThemePkgName);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // don't care since we'll handle res being null below
            }
        }

        return res != null ? res : super.getResources();
    }

    public void setQuiescentAlpha(float alpha, boolean animate) {
        if (mHasSingleAction && (mActions.singleAction == ACTION_LAYOUT_RIGHT
                || mActions.singleAction == ACTION_LAYOUT_LEFT)) {
            return;
        }
        mAnimateToQuiescent.cancel();
        alpha = Math.min(Math.max(alpha, 0), 1);
        if (alpha == mQuiescentAlpha && alpha == mDrawingAlpha) return;
        mQuiescentAlpha = getQuiescentAlphaScale() * alpha;
        if (DEBUG) Log.d(TAG, "New quiescent alpha = " + mQuiescentAlpha);
        if (animate) {
            mAnimateToQuiescent = animateToQuiescent();
            mAnimateToQuiescent.start();
        } else {
            setDrawingAlpha(mQuiescentAlpha);
        }
    }

    private ObjectAnimator animateToQuiescent() {
        return ObjectAnimator.ofFloat(this, "drawingAlpha", mQuiescentAlpha);
    }

    public float getQuiescentAlpha() {
        return mQuiescentAlpha;
    }

    public float getDrawingAlpha() {
        return mDrawingAlpha;
    }

    protected float getQuiescentAlphaScale() {
        return 1.0f;
    }

    public void setDrawingAlpha(float x) {
        setImageAlpha((int) (x * 255));
        mDrawingAlpha = x;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        int x, y;

        mPm.cpuBoost(750000);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownTime = SystemClock.uptimeMillis();
                setPressed(true);
                if (mHasSingleAction) {
                    removeCallbacks(mSingleTap);
                    if (mIsDPadAction) {
                        mShouldClick = true;
                        removeCallbacks(mDPadKeyRepeater);
                    }
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                long diff = mDownTime - mUpTime; // difference between last up and now
                if (mHasDoubleAction && diff <= 200) {
                    doDoubleTap();
                } else {
                    if (mIsDPadAction) {
                        postDelayed(mDPadKeyRepeater, DPAD_TIMEOUT_INTERVAL);
                    } else {
                        if (mHasLongAction) {
                            removeCallbacks(mCheckLongPress);
                            postDelayed(mCheckLongPress, mLongPressTimeout);
                        }
                        if (mHasSingleAction) {
                            postDelayed(mSingleTap, 200);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                x = (int)ev.getX();
                y = (int)ev.getY();
                setPressed(x >= -mTouchSlop
                        && x < getWidth() + mTouchSlop
                        && y >= -mTouchSlop
                        && y < getHeight() + mTouchSlop);
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mIsDPadAction) {
                    mShouldClick = true;
                    removeCallbacks(mDPadKeyRepeater);
                }
                if (mHasSingleAction) {
                    removeCallbacks(mSingleTap);
                }
                if (mHasLongAction) {
                    removeCallbacks(mCheckLongPress);
                }
                // hack to fix ripple getting stuck. exitHardware() starts an animation,
                // but sometimes does not finish it.
                mRipple.exitSoftware();
                break;
            case MotionEvent.ACTION_UP:
                mUpTime = SystemClock.uptimeMillis();
                boolean playSound;

                if (mIsDPadAction) {
                    playSound = mShouldClick;
                    mShouldClick = true;
                    removeCallbacks(mDPadKeyRepeater);
                } else {
                    if (mHasLongAction) {
                        removeCallbacks(mCheckLongPress);
                    }
                    playSound = isPressed();
                }
                setPressed(false);

                if (playSound) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                }

                if (!mHasDoubleAction && !mHasLongAction) {
                    removeCallbacks(mSingleTap);
                    doSinglePress();
                }
                break;
        }
        return true;
    }

    protected void doSinglePress() {
        if (callOnClick()) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        }
        if (mHasSingleAction) {
            CMRemixActions.launchAction(mContext, mActions.singleAction);
        }
    }

    private void doDoubleTap() {
        if (mHasDoubleAction) {
            removeCallbacks(mSingleTap);
            CMRemixActions.launchAction(mContext, mActions.doubleTapAction);
        }
    }

    protected void doLongPress() {
        if (mHasLongAction) {
            removeCallbacks(mSingleTap);
            CMRemixActions.launchAction(mContext, mActions.longPressAction);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
        }
    }

    private Runnable mDPadKeyRepeater = new Runnable() {
        @Override
        public void run() {
            if (mIsDPadAction) {
                CMRemixActions.launchAction(mContext, mActions.singleAction);
                // click on the first event since we're handling in MotionEvent.ACTION_DOWN
                if (mShouldClick) {
                    mShouldClick = false;
                    playSoundEffect(SoundEffectConstants.CLICK);
                }
            }
            // repeat action
            postDelayed(this, DPAD_REPEAT_INTERVAL);
        }
    };

    public void playSoundEffect(int soundConstant) {
        if (mAudioManager != null) {
            mAudioManager.playSoundEffect(soundConstant, ActivityManager.getCurrentUser());
        }
    };

    public void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, int flags, long when) {
        final int repeatCount = (flags & KeyEvent.FLAG_LONG_PRESS) != 0 ? 1 : 0;
        final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, repeatCount,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        InputManager.getInstance().injectInputEvent(ev,
        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void setTint(boolean tint) {
        setColorFilter(null);
        int color = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_TINT, -1);
        boolean hasTint = (color != 0xff000000 && color != -1);
        if (/* tint && */ hasTint) {
            tint = true;
            setColorFilter(color, PorterDuff.Mode.MULTIPLY);
        } else {
            tint = false;
            Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_TINT, -1);
        }
        mShouldTintIcons = tint;
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_TINT),
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    protected void updateSettings() {
        setTint(mShouldTintIcons);
        invalidate();
    }
}
