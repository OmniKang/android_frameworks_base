/*
 * Copyright (C) 2013 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013, OmniRom Project.
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
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.R;

class QuickSettingsBasicTile extends QuickSettingsTileView {
    private final TextView mTextView;
    private final ImageView mImageView;
    private final ProgressBar mLoadingView;

    public QuickSettingsBasicTile(Context context) {
        this(context, null);
    }

    public QuickSettingsBasicTile(Context context, AttributeSet attrs) {
        this(context, attrs, R.layout.quick_settings_tile_basic);
    }

    public QuickSettingsBasicTile(Context context, AttributeSet attrs, int layoutId) {
        super(context, attrs);

        setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            context.getResources().getDimensionPixelSize(R.dimen.quick_settings_cell_height)
        ));
        setBackgroundResource(R.drawable.qs_tile_background);
        addView(LayoutInflater.from(context).inflate(layoutId, null),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        mTextView = (TextView) findViewById(R.id.text);
        mImageView = (ImageView) findViewById(R.id.image);
        mLoadingView = (ProgressBar) findViewById(R.id.loading);
    }

    @Override
    void setContent(int layoutId, LayoutInflater inflater) {
        throw new RuntimeException("why?");
    }

    public ImageView getImageView() {
        return mImageView;
    }

    public TextView getTextView() {
        return mTextView;
    }

    public void setImageDrawable(Drawable drawable) {
        mImageView.setImageDrawable(drawable);
    }

    public void setImageResource(int resId) {
        mImageView.setImageResource(resId);
    }

    public void setText(CharSequence text) {
        mTextView.setText(text);
    }

    void setTextSizes(int size) {
        mTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    void switchToRibbonMode() {
        TextView tv = (TextView) findViewById(R.id.text);
        if (tv != null) {
            tv.setVisibility(View.GONE);
        }
        View image = findViewById(R.id.image);
        if (image != null) {
            MarginLayoutParams params = (MarginLayoutParams) image.getLayoutParams();
            int margin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_tile_ribbon_icon_margin);
            params.topMargin = params.bottomMargin = margin;
            image.setLayoutParams(params);
        }
        View loadingImage = findViewById(R.id.loading);
        if (loadingImage != null) {
            MarginLayoutParams params = (MarginLayoutParams) loadingImage.getLayoutParams();
            int margin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_tile_ribbon_icon_margin);
            params.topMargin = params.bottomMargin = margin;
            loadingImage.setLayoutParams(params);
        }
    }

    public void setTextResource(int resId) {
        mTextView.setText(resId);
    }

    public void setLoading(boolean loading) {
        mLoadingView.setVisibility(loading ? View.VISIBLE : View.GONE);
        mImageView.setVisibility(loading ? View.GONE : View.VISIBLE);
    }
}
