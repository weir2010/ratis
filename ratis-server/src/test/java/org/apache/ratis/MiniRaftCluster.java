/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis;

import com.google.common.base.Preconditions;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientRequestSender;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.impl.DelayLocalExecutionInjection;
import org.apache.ratis.server.impl.RaftConfiguration;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.apache.ratis.server.impl.ServerImplUtils;
import org.apache.ratis.server.storage.MemoryRaftLog;
import org.apache.ratis.server.storage.RaftLog;
import org.apache.ratis.statemachine.BaseStateMachine;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.ExitUtils;
import org.apache.ratis.util.FileUtils;
import org.apache.ratis.util.RaftUtils;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.ratis.server.RaftServerConfigKeys.RAFT_SERVER_RPC_TIMEOUT_MIN_MS_DEFAULT;

public abstract class MiniRaftCluster {
  public static final Logger LOG = LoggerFactory.getLogger(MiniRaftCluster.class);
  public static final DelayLocalExecutionInjection logSyncDelay =
      new DelayLocalExecutionInjection(RaftLog.LOG_SYNC);

  public static final String CLASS_NAME = MiniRaftCluster.class.getSimpleName();
  public static final String STATEMACHINE_CLASS_KEY = CLASS_NAME + ".statemachine.class";
  public static final Class<? extends StateMachine> STATEMACHINE_CLASS_DEFAULT = BaseStateMachine.class;

  public static abstract class Factory<CLUSTER extends MiniRaftCluster> {
    public abstract CLUSTER newCluster(
        String[] ids, RaftProperties prop, boolean formatted)
        throws IOException;

    public CLUSTER newCluster(
        int numServer, RaftProperties prop, boolean formatted)
        throws IOException {
      return newCluster(generateIds(numServer, 0), prop, formatted);
    }
  }

  public static abstract class RpcBase extends MiniRaftCluster {
    public RpcBase(String[] ids, RaftProperties properties, boolean formatted) {
      super(ids, properties, formatted);
    }

    protected abstract RaftServerImpl setPeerRpc(RaftPeer peer) throws IOException;

    @Override
    protected void setPeerRpc() throws IOException {
      for (RaftPeer p : conf.getPeers()) {
        setPeerRpc(p);
      }
    }

    @Override
    public void restartServer(String id, boolean format) throws IOException {
      super.restartServer(id, format);
      setPeerRpc(conf.getPeer(id)).start();
    }

    @Override
    public void setBlockRequestsFrom(String src, boolean block) {
      RaftTestUtil.setBlockRequestsFrom(src, block);
    }
  }

  public static class PeerChanges {
    public final RaftPeer[] allPeersInNewConf;
    public final RaftPeer[] newPeers;
    public final RaftPeer[] removedPeers;

    public PeerChanges(RaftPeer[] all, RaftPeer[] newPeers, RaftPeer[] removed) {
      this.allPeersInNewConf = all;
      this.newPeers = newPeers;
      this.removedPeers = removed;
    }
  }

  public static RaftConfiguration initConfiguration(int numServers) {
    return initConfiguration(generateIds(numServers, 0));
  }

  public static RaftConfiguration initConfiguration(String[] ids) {
    return RaftConfiguration.newBuilder()
        .setConf(Arrays.stream(ids).map(RaftPeer::new).collect(Collectors.toList()))
        .build();
  }

  private static String getBaseDirectory() {
    return System.getProperty("test.build.data", "target/test/data") + "/raft/";
  }

  private static void formatDir(String dirStr) {
    final File serverDir = new File(dirStr);
    Preconditions.checkState(FileUtils.fullyDelete(serverDir),
        "Failed to format directory %s", dirStr);
    LOG.info("Formatted directory {}", dirStr);
  }

  public static String[] generateIds(int numServers, int base) {
    String[] ids = new String[numServers];
    for (int i = 0; i < numServers; i++) {
      ids[i] = "s" + (i + base);
    }
    return ids;
  }

  protected RaftConfiguration conf;
  protected final RaftProperties properties;
  private final String testBaseDir;
  protected final Map<String, RaftServerImpl> servers =
      Collections.synchronizedMap(new LinkedHashMap<>());

  public MiniRaftCluster(String[] ids, RaftProperties properties,
      boolean formatted) {
    this.conf = initConfiguration(ids);
    this.properties = new RaftProperties(properties);
    this.testBaseDir = getBaseDirectory();

    conf.getPeers().forEach(
        p -> servers.put(p.getId(), newRaftServer(p.getId(), formatted)));

    ExitUtils.disableSystemExit();
  }

  protected <RPC extends  RaftServerRpc> void init(Map<RaftPeer, RPC> peers) {
    LOG.info("peers = " + peers.keySet());
    conf = RaftConfiguration.newBuilder().setConf(peers.keySet()).build();
    for (Map.Entry<RaftPeer, RPC> entry : peers.entrySet()) {
      final RaftServerImpl server = servers.get(entry.getKey().getId());
      server.setInitialConf(conf);
      server.setServerRpc(entry.getValue());
    }
  }

  public void start() {
    LOG.info("Starting " + getClass().getSimpleName());
    servers.values().forEach(RaftServerImpl::start);
  }

  /**
   * start a stopped server again.
   */
  public void restartServer(String id, boolean format) throws IOException {
    killServer(id);
    servers.remove(id);
    servers.put(id, newRaftServer(id, format));
  }

  public final void restart(boolean format) throws IOException {
    servers.values().stream().filter(RaftServerImpl::isAlive)
        .forEach(RaftServerImpl::close);
    List<String> idList = new ArrayList<>(servers.keySet());
    for (String id : idList) {
      servers.remove(id);
      servers.put(id, newRaftServer(id, format));
    }

    setPeerRpc();
    start();
  }

  protected abstract void setPeerRpc() throws IOException;

  public int getMaxTimeout() {
    return properties.getInt(
        RaftServerConfigKeys.RAFT_SERVER_RPC_TIMEOUT_MAX_MS_KEY,
        RaftServerConfigKeys.RAFT_SERVER_RPC_TIMEOUT_MAX_MS_DEFAULT);
  }

  public RaftConfiguration getConf() {
    return conf;
  }

  private RaftServerImpl newRaftServer(String id, boolean format) {
    final RaftServerImpl s;
    try {
      final String dirStr = testBaseDir + id;
      if (format) {
        formatDir(dirStr);
      }
      properties.set(RaftServerConfigKeys.RAFT_SERVER_STORAGE_DIR_KEY, dirStr);
      final StateMachine stateMachine = getStateMachine4Test(properties);
      s = ServerImplUtils.newRaftServer(id, stateMachine, conf, properties);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return s;
  }

  static StateMachine getStateMachine4Test(RaftProperties properties) {
    final Class<? extends StateMachine> smClass = properties.getClass(
        STATEMACHINE_CLASS_KEY,
        STATEMACHINE_CLASS_DEFAULT,
        StateMachine.class);
    return RaftUtils.newInstance(smClass);
  }

  public abstract RaftClientRequestSender getRaftClientRequestSender();

  protected <RPC extends RaftServerRpc> Collection<RaftPeer> addNewPeers(
      Map<RaftPeer, RPC> newPeers, Collection<RaftServerImpl> newServers,
      boolean startService) throws IOException {
    for (Map.Entry<RaftPeer, RPC> entry : newPeers.entrySet()) {
      RaftServerImpl server = servers.get(entry.getKey().getId());
      server.setServerRpc(entry.getValue());
    }
    if (startService) {
      newServers.forEach(RaftServerImpl::start);
    }
    return new ArrayList<>(newPeers.keySet());
  }

  protected abstract Collection<RaftPeer> addNewPeers(
      Collection<RaftPeer> newPeers, Collection<RaftServerImpl> newServers,
      boolean startService) throws IOException;

  public PeerChanges addNewPeers(int number, boolean startNewPeer)
      throws IOException {
    return addNewPeers(generateIds(number, servers.size()), startNewPeer);
  }

  public PeerChanges addNewPeers(String[] ids,
      boolean startNewPeer) throws IOException {
    LOG.info("Add new peers {}", Arrays.asList(ids));
    Collection<RaftPeer> newPeers = new ArrayList<>(ids.length);
    for (String id : ids) {
      newPeers.add(new RaftPeer(id));
    }

    // create and add new RaftServers
    final List<RaftServerImpl> newServers = new ArrayList<>(ids.length);
    for (RaftPeer p : newPeers) {
      RaftServerImpl newServer = newRaftServer(p.getId(), true);
      Preconditions.checkArgument(!servers.containsKey(p.getId()));
      servers.put(p.getId(), newServer);
      newServers.add(newServer);
    }

    // for hadoop-rpc-enabled peer, we assign inetsocketaddress here
    newPeers = addNewPeers(newPeers, newServers, startNewPeer);

    final RaftPeer[] np = newPeers.toArray(new RaftPeer[newPeers.size()]);
    newPeers.addAll(conf.getPeers());
    conf = RaftConfiguration.newBuilder().setConf(newPeers).setLogEntryIndex(0).build();
    RaftPeer[] p = newPeers.toArray(new RaftPeer[newPeers.size()]);
    return new PeerChanges(p, np, new RaftPeer[0]);
  }

  public void startServer(String id) {
    RaftServerImpl server = servers.get(id);
    assert server != null;
    server.start();
  }

  private RaftPeer getPeer(RaftServerImpl s) {
    return new RaftPeer(s.getId(), s.getServerRpc().getInetSocketAddress());
  }

  /**
   * prepare the peer list when removing some peers from the conf
   */
  public PeerChanges removePeers(int number, boolean removeLeader,
      Collection<RaftPeer> excluded) {
    Collection<RaftPeer> peers = new ArrayList<>(conf.getPeers());
    List<RaftPeer> removedPeers = new ArrayList<>(number);
    if (removeLeader) {
      final RaftPeer leader = getPeer(getLeader());
      assert !excluded.contains(leader);
      peers.remove(leader);
      removedPeers.add(leader);
    }
    List<RaftServerImpl> followers = getFollowers();
    for (int i = 0, removed = 0; i < followers.size() &&
        removed < (removeLeader ? number - 1 : number); i++) {
      RaftPeer toRemove = getPeer(followers.get(i));
      if (!excluded.contains(toRemove)) {
        peers.remove(toRemove);
        removedPeers.add(toRemove);
        removed++;
      }
    }
    conf = RaftConfiguration.newBuilder().setConf(peers).setLogEntryIndex(0).build();
    RaftPeer[] p = peers.toArray(new RaftPeer[peers.size()]);
    return new PeerChanges(p, new RaftPeer[0],
        removedPeers.toArray(new RaftPeer[removedPeers.size()]));
  }

  public void killServer(String id) {
    servers.get(id).close();
  }

  public String printServers() {
    StringBuilder b = new StringBuilder("\n#servers = " + servers.size() + "\n");
    for (RaftServerImpl s : servers.values()) {
      b.append("  ");
      b.append(s).append("\n");
    }
    return b.toString();
  }

  public String printAllLogs() {
    StringBuilder b = new StringBuilder("\n#servers = " + servers.size() + "\n");
    for (RaftServerImpl s : servers.values()) {
      b.append("  ");
      b.append(s).append("\n");

      final RaftLog log = s.getState().getLog();
      if (log instanceof MemoryRaftLog) {
        b.append("    ");
        b.append(((MemoryRaftLog) log).getEntryString());
      }
    }
    return b.toString();
  }

  public RaftServerImpl getLeader() {
    final List<RaftServerImpl> leaders = new ArrayList<>();
    servers.values().stream()
        .filter(s -> s.isAlive() && s.isLeader())
        .forEach(s -> {
      if (leaders.isEmpty()) {
        leaders.add(s);
      } else {
        final long leaderTerm = leaders.get(0).getState().getCurrentTerm();
        final long term = s.getState().getCurrentTerm();
        if (term >= leaderTerm) {
          if (term > leaderTerm) {
            leaders.clear();
          }
          leaders.add(s);
        }
      }
    });
    if (leaders.isEmpty()) {
      return null;
    } else if (leaders.size() != 1) {
      Assert.fail(printServers() + leaders.toString()
          + "leaders.size() = " + leaders.size() + " != 1");
    }
    return leaders.get(0);
  }

  public boolean isLeader(String leaderId) throws InterruptedException {
    final RaftServerImpl leader = getLeader();
    return leader != null && leader.getId().equals(leaderId);
  }

  public List<RaftServerImpl> getFollowers() {
    return servers.values().stream()
        .filter(s -> s.isAlive() && s.isFollower())
        .collect(Collectors.toList());
  }

  public Collection<RaftServerImpl> getServers() {
    return servers.values();
  }

  public RaftServerImpl getServer(String id) {
    return servers.get(id);
  }

  public Collection<RaftPeer> getPeers() {
    return getServers().stream().map(s ->
        new RaftPeer(s.getId(), s.getServerRpc().getInetSocketAddress()))
        .collect(Collectors.toList());
  }

  public RaftClient createClient(String clientId, String leaderId) {
    return RaftClient.newBuilder()
        .setClientId(clientId)
        .setServers(conf.getPeers())
        .setLeaderId(leaderId)
        .setRequestSender(getRaftClientRequestSender())
        .setProperties(properties)
        .build();
  }

  public void shutdown() {
    LOG.info("Stopping " + getClass().getSimpleName());
    servers.values().stream().filter(RaftServerImpl::isAlive)
        .forEach(RaftServerImpl::close);

    if (ExitUtils.isTerminated()) {
      LOG.error("Test resulted in an unexpected exit",
          ExitUtils.getFirstExitException());
      throw new AssertionError("Test resulted in an unexpected exit");
    }
  }

  /**
   * Block all the incoming requests for the peer with leaderId. Also delay
   * outgoing or incoming msg for all other peers.
   */
  protected abstract void blockQueueAndSetDelay(String leaderId, int delayMs)
      throws InterruptedException;

  /**
   * Try to enforce the leader of the cluster.
   * @param leaderId ID of the targeted leader server.
   * @return true if server has been successfully enforced to the leader, false
   *         otherwise.
   */
  public boolean tryEnforceLeader(String leaderId) throws InterruptedException {
    // do nothing and see if the given id is already a leader.
    if (isLeader(leaderId)) {
      return true;
    }

    // Blocking all other server's RPC read process to make sure a read takes at
    // least ELECTION_TIMEOUT_MIN. In this way when the target leader request a
    // vote, all non-leader servers can grant the vote.
    // Disable the target leader server RPC so that it can request a vote.
    blockQueueAndSetDelay(leaderId, RAFT_SERVER_RPC_TIMEOUT_MIN_MS_DEFAULT);

    // Reopen queues so that the vote can make progress.
    blockQueueAndSetDelay(leaderId, 0);

    return isLeader(leaderId);
  }

  /** Block/unblock the requests sent from the given source. */
  public abstract void setBlockRequestsFrom(String src, boolean block);
}
