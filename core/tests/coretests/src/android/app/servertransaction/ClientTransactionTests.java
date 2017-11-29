/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
// TODO(lifecycler): Add to presubmit after checking for flakiness.
public class ClientTransactionTests {

    @Test
    public void testPrepare() {
        ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        ClientTransactionHandler clientTransactionHandler = mock(ClientTransactionHandler.class);
        IBinder token = mock(IBinder.class);

        ClientTransaction transaction = new ClientTransaction(null /* client */,
                token /* activityToken */);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(stateRequest);

        transaction.prepare(clientTransactionHandler);

        verify(callback1, times(1)).prepare(clientTransactionHandler, token);
        verify(callback2, times(1)).prepare(clientTransactionHandler, token);
        verify(stateRequest, times(1)).prepare(clientTransactionHandler, token);
    }

    @Test
    public void testExecute() {
        ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        IBinder token = mock(IBinder.class);

        ClientTransaction transaction = new ClientTransaction(null /* client */,
                token /* activityToken */);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(stateRequest);

        ClientTransactionHandler clientTransactionHandler = mock(ClientTransactionHandler.class);
        transaction.prepare(clientTransactionHandler);
        transaction.execute(clientTransactionHandler);

        verify(callback1, times(1)).execute(clientTransactionHandler, token);
        verify(callback2, times(1)).execute(clientTransactionHandler, token);
        verify(stateRequest, times(1)).execute(clientTransactionHandler, token);
    }
}
