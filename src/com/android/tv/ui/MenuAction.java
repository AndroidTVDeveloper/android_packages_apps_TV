/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tv.ui;

import android.content.Context;

import com.android.tv.R;

/**
 * A class to define possible actions from main menu.
 */
public class MenuAction {
    public static final int SELECT_CLOSED_CAPTION = 0;
    public static final int SELECT_DISPLAY_MODE = 1;
    public static final int SELECT_TV_INPUT_TYPE = 2;
    public static final int TOGGLE_PIP_TYPE = 3;

    // TODO: UX is not ready.
    public static final int EDIT_CHANNEL_LIST_TYPE = 4;
    public static final int AUTO_SCAN_CHANNELS_TYPE = 5;
    public static final int INPUT_SETTING_TYPE = 6;

    public static final MenuAction SELECT_CLOSED_CAPTION_ACTION =
            new MenuAction(R.string.menu_closed_caption, SELECT_CLOSED_CAPTION,
                    R.drawable.ic_tvoption_cc);
    public static final MenuAction SELECT_DISPLAY_MODE_ACTION =
            new MenuAction(R.string.menu_display_mode, SELECT_DISPLAY_MODE,
                    R.drawable.ic_tvoption_display_mode);
    public static final MenuAction SELECT_TV_INPUT_ACTION =
            new MenuAction(R.string.menu_select_input, SELECT_TV_INPUT_TYPE,
                    R.drawable.ic_tvoptions_input_settings);
    public static final MenuAction TOGGLE_PIP_ACTION =
            new MenuAction(R.string.menu_toggle_pip, TOGGLE_PIP_TYPE,
                    R.drawable.ic_tvoption_pip);

    // TODO: UX is not ready.
    public static final MenuAction EDIT_CHANNEL_LIST_ACTION =
            new MenuAction(R.string.menu_edit_channels, EDIT_CHANNEL_LIST_TYPE,
                    R.drawable.ic_tvoptions_edit_channels);
    public static final MenuAction AUTO_SCAN_CHANNELS_ACTION =
            new MenuAction(R.string.menu_auto_scan, AUTO_SCAN_CHANNELS_TYPE,
                    R.drawable.ic_tvoptions_scan);
    public static final MenuAction INPUT_SETTING_ACTION =
            new MenuAction(R.string.menu_input_setting, INPUT_SETTING_TYPE,
                    R.drawable.ic_tvoption_more);

    private final int mActionNameResId;
    private final int mType;
    private final int mDrawableResId;

    public MenuAction(int actionNameResId, int type, int drawableResId) {
        mActionNameResId = actionNameResId;
        mType = type;
        mDrawableResId = drawableResId;
    }

    public String getActionName(Context context) {
        return context.getString(mActionNameResId);
    }

    public int getType() {
        return mType;
    }

    public int getDrawableResId() {
        return mDrawableResId;
    }
}
