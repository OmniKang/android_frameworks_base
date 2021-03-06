/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013, ParanoidAndroid Project.
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.EventLog;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;

import java.io.File;

public class SettingsPanelView extends PanelView {
    public static final boolean DEBUG_GESTURES = true;

    private QuickSettings mQS;
    private QuickSettingsContainerView mQSContainer;

    Drawable mHandleBar;
    int mHandleBarHeight;
    View mHandleView;
    Drawable mBackgroundDrawable;
    ImageView mBackground;

    public SettingsPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mQSContainer = (QuickSettingsContainerView) findViewById(R.id.quick_settings_container);

        Resources resources = getContext().getResources();
        mHandleBar = resources.getDrawable(R.drawable.status_bar_close);
        mHandleBarHeight = resources.getDimensionPixelSize(R.dimen.close_handle_height);
        mHandleView = findViewById(R.id.handle);
        mBackground = (ImageView) findViewById(R.id.notification_wallpaper);
        setBackgroundDrawables();
    }

    public void setQuickSettings(QuickSettings qs) {
        mQS = qs;
    }

    @Override
    public void setBar(PanelBar panelBar) {
        super.setBar(panelBar);

        if (mQS != null) {
            mQS.setBar(panelBar);
        }
    }

    public void setImeWindowStatus(boolean visible) {
        if (mQS != null) {
            mQS.setImeWindowStatus(visible);
        }
    }

    public void setup(NetworkController networkController, BluetoothController bluetoothController,
            BatteryController batteryController, LocationController locationController,
            RotationLockController rotationLockController) {
        if (mQS != null) {
            mQS.setup(networkController, bluetoothController, batteryController,
                    locationController, rotationLockController);
        }
    }

    void updateResources() {
        if (mQS != null) {
            mQS.updateResources();
        }
        if (mQSContainer != null) {
            mQSContainer.updateResources();
        }
        requestLayout();
    }

    @Override
    public void fling(float vel, boolean always) {
        if (DEBUG_GESTURES) {
            GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
            if (gr != null) {
                gr.tag(
                    "fling " + ((vel > 0) ? "open" : "closed"),
                    "settings,v=" + vel);
            }
        }
        super.fling(vel, always);
    }

    public void setService(PhoneStatusBar phoneStatusBar) {
        if (mQS != null) {
            mQS.setService(phoneStatusBar);
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.getText()
                    .add(getContext().getString(R.string.accessibility_desc_quick_settings));
            return true;
        }

        return super.dispatchPopulateAccessibilityEvent(event);
    }

    // We draw the handle ourselves so that it's always glued to the bottom of the window.
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            final int pl = getPaddingLeft();
            final int pr = getPaddingRight();
            mHandleBar.setBounds(pl, 0, getWidth() - pr, (int) mHandleBarHeight);
        }
    }

    @Override
    public void collapse() {
        if(mQSContainer.isEditModeEnabled()) mQSContainer.setEditModeEnabled(false);
        super.collapse();
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        final int off = (int) (getHeight() - mHandleBarHeight - getPaddingBottom());
        canvas.translate(0, off);
        mHandleBar.setState(mHandleView.getDrawableState());
        mHandleBar.draw(canvas);
        canvas.translate(0, -off);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_QUICKPANEL_TOUCH,
                       event.getActionMasked(), (int) event.getX(), (int) event.getY());
            }
        }
        return super.onTouchEvent(event);
    }

    private void setDefaultBackground(int resource, int color, int alpha) {
        setBackgroundResource(resource);
        if (color != -2) {
            getBackground().setColorFilter(color, Mode.SRC_ATOP);
        } else {
            getBackground().setColorFilter(null);
        }
        getBackground().setAlpha(alpha);
        mBackgroundDrawable = null;
        mBackground.setImageDrawable(null);
    }

    protected void setBackgroundDrawables() {
        float alpha = Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.NOTIFICATION_BACKGROUND_ALPHA, 0.1f,
                UserHandle.USER_CURRENT);
        int backgroundAlpha = (int) ((1 - alpha) * 255);

        String notifiBack = Settings.System.getStringForUser(
                mContext.getContentResolver(),
                Settings.System.NOTIFICATION_BACKGROUND,
                UserHandle.USER_CURRENT);

        if (notifiBack == null) {
            setDefaultBackground(R.drawable.notification_panel_bg, -2, backgroundAlpha);
            return;
        }

        if (notifiBack.startsWith("color=")) {
            notifiBack = notifiBack.substring("color=".length());
            try {
                setDefaultBackground(R.drawable.notification_panel_bg,
                        Color.parseColor(notifiBack), backgroundAlpha);
            } catch(NumberFormatException e) {
            }
        } else {
            File f = new File(Uri.parse(notifiBack).getPath());
            if (f !=  null) {
                Bitmap backgroundBitmap = BitmapFactory.decodeFile(f.getAbsolutePath());
                mBackgroundDrawable =
                    new BitmapDrawable(getContext().getResources(), backgroundBitmap);
            }
        }
        if (mBackgroundDrawable != null) {
            setBackgroundResource(com.android.internal.R.color.transparent);
            mBackgroundDrawable.setAlpha(backgroundAlpha);
            mBackground.setImageDrawable(mBackgroundDrawable);
        }
    }

}
