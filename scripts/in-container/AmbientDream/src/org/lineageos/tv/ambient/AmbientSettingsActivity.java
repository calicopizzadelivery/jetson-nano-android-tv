/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.ambient;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where the weather line gets its location.
 *
 * Declared as the dream's {@code settingsActivity}, which is the metadata the
 * platform already reads into {@code DreamBackend.DreamInfo}. TvSettings does
 * not surface it as shipped — see patches/TvSettings/0002 in this project —
 * so on an unpatched build this screen is reachable only by component name.
 */
public class AmbientSettingsActivity extends FragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(
                    this, new LocationFragment(), android.R.id.content);
        }
    }

    public static class LocationFragment extends GuidedStepSupportFragment {

        private static final int ACTION_PLACE = 1;
        private static final int ACTION_AUTO = 2;

        private final ExecutorService mIo = Executors.newSingleThreadExecutor();

        @Override
        @NonNull
        public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
            return new GuidanceStylist.Guidance(
                    getString(R.string.settings_title),
                    describeCurrent(),
                    getString(R.string.dream_label),
                    null);
        }

        @Override
        public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle args) {
            final Context context = requireContext();
            final String place = Weather.getPlace(context);

            // title is what the row shows; editTitle is what the edit box
            // starts with. They differ when the location is automatic: the row
            // says "Automatic" but the box must open empty, or the placeholder
            // becomes the first word of whatever is typed.
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_PLACE)
                    .title(place.isEmpty() ? getString(R.string.settings_place_auto) : place)
                    .editTitle(place)
                    .description(R.string.settings_place_description)
                    .editable(true)
                    .editInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                    .build());

            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_AUTO)
                    .title(R.string.settings_auto)
                    .description(R.string.settings_auto_description)
                    .build());
        }

        @Override
        public long onGuidedActionEditedAndProceed(@NonNull GuidedAction action) {
            if (action.getId() == ACTION_PLACE) {
                // GuidedActionAdapterGroup.updateTextIntoAction writes the typed
                // text to editTitle whenever editTitle is non-null, which it
                // always is here. Reading getTitle() would return the stale
                // display text.
                final CharSequence typed = action.getEditTitle();
                apply(typed == null ? "" : typed.toString());
            }
            return GuidedAction.ACTION_ID_CURRENT;
        }

        @Override
        public void onGuidedActionClicked(@NonNull GuidedAction action) {
            if (action.getId() == ACTION_AUTO) {
                apply("");
            }
        }

        @Override
        public void onDestroy() {
            mIo.shutdownNow();
            super.onDestroy();
        }

        /**
         * Store the place and resolve it straight away. Resolving here rather
         * than leaving it to the dream is the point of the screen: a typed
         * place that geocodes to nothing should say so now, not silently leave
         * the weather line blank until someone notices.
         */
        private void apply(String place) {
            final Context app = requireContext().getApplicationContext();
            Weather.setPlace(app, place);
            setStatus(getString(R.string.settings_checking));

            mIo.execute(() -> {
                final String city = Weather.resolveNow(app);
                final Activity activity = getActivity();
                if (activity == null) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (city == null) {
                        setStatus(getString(place.isEmpty()
                                ? R.string.settings_no_location
                                : R.string.settings_not_found));
                    } else {
                        setStatus(describeCurrent());
                    }
                    retitlePlaceAction();
                });
            });
        }

        private void retitlePlaceAction() {
            final GuidedAction action = findActionById(ACTION_PLACE);
            if (action == null) {
                return;
            }
            final String place = Weather.getPlace(requireContext());
            action.setTitle(place.isEmpty() ? getString(R.string.settings_place_auto) : place);
            action.setEditTitle(place);
            notifyActionChanged(findActionPositionById(ACTION_PLACE));
        }

        private void setStatus(String text) {
            if (getGuidanceStylist() != null
                    && getGuidanceStylist().getDescriptionView() != null) {
                getGuidanceStylist().getDescriptionView().setText(text);
            }
        }

        private String describeCurrent() {
            final Context context = requireContext();
            final String city = Weather.getCity(context);
            if (TextUtils.isEmpty(city)) {
                return getString(R.string.settings_no_location);
            }
            return getString(Weather.getPlace(context).isEmpty()
                            ? R.string.settings_showing_auto
                            : R.string.settings_showing_set,
                    city);
        }
    }
}
