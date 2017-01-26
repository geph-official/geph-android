package io.geph.android.api.tasks;

import java.io.IOException;

import io.geph.android.api.GephService;
import io.geph.android.api.GephServiceTask;
import io.geph.android.api.models.AccountInfo;

/**
 * @author j3sawyer
 */
public class GetAccountInfoTask implements GephServiceTask<Object, AccountInfo> {
    @Override
    public AccountInfo handle(GephService service, Object... params) {
        try {
            return service.getAccountInfo().execute().body();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
