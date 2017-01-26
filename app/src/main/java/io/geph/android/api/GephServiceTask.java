package io.geph.android.api;

/**
 * @author j3sawyer
 */
public interface GephServiceTask<T, K> {
    K handle(GephService service, T ... params);
}
