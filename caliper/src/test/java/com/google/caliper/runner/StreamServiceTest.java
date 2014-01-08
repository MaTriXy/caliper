/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.caliper.runner;

import static com.google.common.base.Preconditions.checkState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.caliper.bridge.LogMessage;
import com.google.caliper.bridge.LogMessageVisitor;
import com.google.caliper.options.CaliperOptions;
import com.google.caliper.runner.ServerSocketService.OpenedSocket;
import com.google.caliper.runner.StreamService.StreamItem;
import com.google.caliper.runner.StreamService.StreamItem.Kind;
import com.google.caliper.util.Parser;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service.Listener;
import com.google.common.util.concurrent.Service.State;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.SocketException;
import java.text.ParseException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link StreamService}.
 */
@RunWith(JUnit4.class)

public class StreamServiceTest {

  private ServerSocket serverSocket;
  @Mock CaliperOptions options;
  private final StringWriter writer = new StringWriter();
  private final PrintWriter stdout = new PrintWriter(writer, true);
  private final Parser<LogMessage> parser = new Parser<LogMessage>() {
    @Override public LogMessage parse(final CharSequence text) throws ParseException {
      return new LogMessage() {
        @Override public void accept(LogMessageVisitor visitor) {}
        @Override  public String toString() {
          return text.toString();
        }
      };
    }
  };
  private StreamService service;
  private final CountDownLatch terminalLatch = new CountDownLatch(1);
  private static final int TRIAL_NUMBER = 3;

  @Before public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    Mockito.when(options.verbose()).thenReturn(true);
    serverSocket = new ServerSocket(0);
  }

  @After public void closeSocket() throws IOException {
    serverSocket.close();
  }

  @After public void stopService() {
    if (service != null && service.state() != State.FAILED && service.state() != State.TERMINATED) {
      service.stopAsync().awaitTerminated();
    }
  }

  @Test public void testReadOutput() throws Exception {
    makeService("echo foo && echo bar 1>&2");
    service.startAsync().awaitRunning();
    StreamItem item1 = readItem();
    assertEquals(Kind.DATA, item1.kind());
    Set<String> lines = Sets.newHashSet();
    lines.add(item1.content().toString());
    StreamItem item2 = readItem();
    assertEquals(Kind.DATA, item2.kind());
    lines.add(item2.content().toString());
    assertEquals(Sets.newHashSet("foo", "bar"), lines);
    assertEquals(State.RUNNING, service.state());
    StreamItem item3 = readItem();
    assertEquals(Kind.EOF, item3.kind());
    awaitStopped(100, TimeUnit.MILLISECONDS);
    assertTerminated();
  }


  @Test public void failingProcess() throws Exception {
    makeService("exit 1");
    service.startAsync().awaitRunning();
    assertEquals(Kind.EOF, readItem().kind());
    awaitStopped(100, TimeUnit.MILLISECONDS);
    assertEquals(State.FAILED, service.state());
  }

  @Test public void processDoesntExit() throws Exception {
    // close all fds and then sleep
    makeService("exec 0>&- ; exec 1>&- ; exec 2>&- ; sleep 10m");
    service.startAsync().awaitRunning();
    assertEquals(Kind.EOF, readItem().kind());
    awaitStopped(200, TimeUnit.MILLISECONDS);  // we
    assertEquals(State.FAILED, service.state());
  }

  @Test public void testSocketInputOutput() throws Exception {
    int localport = serverSocket.getLocalPort();
    // read from the socket and echo it back
    makeService("exec 3<>/dev/tcp/127.0.0.1/" + localport + ";"
        + "echo start >&3; while read -r line <&3 ; do echo $line >&3; done");

    service.startAsync().awaitRunning();
    assertEquals("start", readItem().content().toString());
    service.writeLine("hello socket world");
    assertEquals("hello socket world", readItem().content().toString());
    service.closeWriter();
    assertEquals(State.RUNNING, service.state());
    assertEquals(Kind.EOF, readItem().kind());
    awaitStopped(100, TimeUnit.MILLISECONDS);
    assertTerminated();
  }

  @Test public void testSocketClosesBeforeProcess() throws Exception {
    int localport = serverSocket.getLocalPort();
    // read from the socket and echo it back
    makeService("exec 3<>/dev/tcp/127.0.0.1/" + localport + ";"
        + "echo start >&3; while read -r line <&3 ; do echo $line >&3; done; exec 3>&-; echo foo");
    service.startAsync().awaitRunning();
    assertEquals("start", readItem().content().toString());
    service.writeLine("hello socket world");
    assertEquals("hello socket world", readItem().content().toString());
    service.closeWriter();

    assertEquals("foo", readItem().content().toString());

    assertEquals(State.RUNNING, service.state());
    assertEquals(Kind.EOF, readItem().kind());
    awaitStopped(100, TimeUnit.MILLISECONDS);
    assertTerminated();
  }

  @Test public void failsToAcceptConnection() throws Exception {
    serverSocket.close();  // This will force serverSocket.accept to throw a SocketException
    makeService("sleep 10m");
    try {
      service.startAsync().awaitRunning();
      fail();
    } catch (IllegalStateException ignored) {}
    assertEquals(SocketException.class, service.failureCause().getClass());
  }

  /** Reads an item, asserting that there was no timeout. */
  private StreamItem readItem() throws InterruptedException {
    StreamItem item = service.readItem(100, TimeUnit.SECONDS);
    assertNotSame("Timed out while reading item from worker", Kind.TIMEOUT, item.kind());
    return item;
  }

  /**
   * Wait for the service to reach a terminal state without calling stop.
   */
  private void awaitStopped(long time, TimeUnit unit) throws InterruptedException {
    assertTrue(terminalLatch.await(time, unit));
  }

  private void assertTerminated() {
    State state = service.state();
    if (state != State.TERMINATED) {
      if (state == State.FAILED) {
        throw new AssertionError(service.failureCause());
      }
      fail("Expected service to be terminated but was: " + state);
    }
  }

  private void makeService(String bashScript) {
    checkState(service == null, "You can only make one StreamService per test");
    service = new StreamService(
        new WorkerProcess(new ProcessBuilder().command("bash", "-c", bashScript),
            UUID.randomUUID(),
            getSocketFuture(),
            new RuntimeShutdownHookRegistrar()),
        TRIAL_NUMBER, parser, options, stdout);
    service.addListener(new Listener() {
      @Override public void starting() {}
      @Override public void running() {}
      @Override public void stopping(State from) {}
      @Override public void terminated(State from) {
        terminalLatch.countDown();
      }
      @Override public void failed(State from, Throwable failure) {
        terminalLatch.countDown();
      }
    }, MoreExecutors.sameThreadExecutor());
  }

  private ListenableFuture<OpenedSocket> getSocketFuture() {
    ListenableFutureTask<OpenedSocket> openSocketTask = ListenableFutureTask.create(
        new Callable<OpenedSocket>() {
          @Override
          public OpenedSocket call() throws Exception {
            return OpenedSocket.fromSocket(serverSocket.accept());
          }
        });
    // N.B. this thread will block on serverSocket.accept until a connection is accepted or the
    // socket is closed, so no matter what this thread will die with the test.
    Thread opener = new Thread(openSocketTask, "SocketOpener");
    opener.setDaemon(true);
    opener.start();
    return openSocketTask;
  }
}
