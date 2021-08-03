/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.dagger;

import android.annotation.Nullable;
import android.view.View;

import com.android.keyguard.LockIconView;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.biometrics.AuthRippleView;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
import com.android.systemui.statusbar.phone.TapAgainView;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

@Module
public abstract class StatusBarViewModule {

    public static final String SPLIT_SHADE_HEADER = "split_shade_header";

    /** */
    @Provides
    @StatusBarComponent.StatusBarScope
    public static NotificationPanelView getNotificationPanelView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.getNotificationPanelView();
    }

    /** */
    @Provides
    @StatusBarComponent.StatusBarScope
    public static LockIconView getLockIconView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.lock_icon_view);
    }

    /** */
    @Provides
    @StatusBarComponent.StatusBarScope
    @Nullable
    public static AuthRippleView getAuthRippleView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.auth_ripple);
    }

    /** */
    @Provides
    @Named(SPLIT_SHADE_HEADER)
    @StatusBarComponent.StatusBarScope
    public static View getSlitShadeStatusBarView(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.split_shade_status_bar);
    }

    /** */
    @Provides
    @StatusBarComponent.StatusBarScope
    static BatteryMeterView getBatteryMeterView(@Named(SPLIT_SHADE_HEADER) View view) {
        return view.findViewById(R.id.batteryRemainingIcon);
    }

    /** */
    @Provides
    @StatusBarComponent.StatusBarScope
    public static TapAgainView getTapAgainView(NotificationPanelView npv) {
        return npv.getTapAgainView();
    }
}
