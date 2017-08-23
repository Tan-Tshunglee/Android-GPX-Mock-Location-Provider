package com.twolinessoftware.android.model;

import android.databinding.ObservableField;

/**
 * User location input data model
 */

public class Location {
    public final ObservableField<String> latitude = new ObservableField<String>("");
    public final ObservableField<String> longitude = new ObservableField<String>("");
}
