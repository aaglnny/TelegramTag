package org.telegram.messenger;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return getPackageName();
    }

    @Override
    protected boolean isAndroidTestEnv() {
        return true;
    }
}
