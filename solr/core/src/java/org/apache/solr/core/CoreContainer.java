/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.core;

import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.ZkSolrResourceLoader;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ZooKeeperException;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.handler.admin.CollectionsHandler;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.handler.component.ShardHandlerFactory;
import org.apache.solr.logging.LogWatcher;
import org.apache.solr.logging.jul.JulWatcher;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.IndexSchemaFactory;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.apache.solr.util.FileUtils;
import org.apache.solr.util.PropertiesUtil;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.lang.String;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;


/**
 *
 * @since solr 1.3
 */
public class CoreContainer
{
  private static final String LEADER_VOTE_WAIT = "180000";  // 3 minutes
  private static final int CORE_LOAD_THREADS = 3;

  private static final int DEFAULT_ZK_CLIENT_TIMEOUT = 15000;
  public static final String DEFAULT_DEFAULT_CORE_NAME = "collection1";
  private static final boolean DEFAULT_SHARE_SCHEMA = false;

  protected static Logger log = LoggerFactory.getLogger(CoreContainer.class);


  private final SolrCores solrCores = new SolrCores(this);

  protected final Map<String,Exception> coreInitFailures =
    Collections.synchronizedMap(new LinkedHashMap<String,Exception>());
  
  protected boolean persistent = false;
  protected String adminPath = null;
  protected volatile String managementPath = null;

  protected CoreAdminHandler coreAdminHandler = null;
  protected CollectionsHandler collectionsHandler = null;
  protected String libDir = null;

  protected Properties containerProperties;
  protected Map<String ,IndexSchema> indexSchemaCache;
  protected String adminHandler;
  protected boolean shareSchema;
  protected Integer zkClientTimeout;
  protected String defaultCoreName = null;
  protected int distribUpdateConnTimeout = 0;
  protected int distribUpdateSoTimeout = 0;

  protected ZkContainer zkSys = new ZkContainer();

  protected ShardHandlerFactory shardHandlerFactory;
  protected LogWatcher logging = null;
  private String zkHost;
  private int transientCacheSize = Integer.MAX_VALUE;

  private int coreLoadThreads;
  private CloserThread backgroundCloser = null;

  protected final ConfigSolr cfg;
  protected final SolrResourceLoader loader;
  protected final String solrHome;
  private String hostPort;

  {
    log.info("New CoreContainer " + System.identityHashCode(this));
  }

  /**
   * Create a new CoreContainer using system properties to detect the solr home
   * directory.  The container's cores are not loaded.
   * @see #load()
   */
  public CoreContainer() {
    this(new SolrResourceLoader(SolrResourceLoader.locateSolrHome()));
  }

  /**
   * Create a new CoreContainer using the given SolrResourceLoader.  The container's
   * cores are not loaded.
   * @param loader the SolrResourceLoader
   * @see #load()
   */
  public CoreContainer(SolrResourceLoader loader) {
    this(loader, ConfigSolr.fromSolrHome(loader, loader.getInstanceDir()));
  }

  /**
   * Create a new CoreContainer using the given solr home directory.  The container's
   * cores are not loaded.
   * @param solrHome a String containing the path to the solr home directory
   * @see #load()
   */
  public CoreContainer(String solrHome) {
    this(new SolrResourceLoader(solrHome));
  }

  /**
   * Create a new CoreContainer using the given SolrResourceLoader and
   * configuration.  The container's cores are not loaded.
   * @param loader the SolrResourceLoader
   * @param config a ConfigSolr representation of this container's configuration
   * @see #load()
   */
  public CoreContainer(SolrResourceLoader loader, ConfigSolr config) {
    this.loader = checkNotNull(loader);
    this.solrHome = loader.getInstanceDir();
    this.cfg = checkNotNull(config);
  }

  /**
   * Create a new CoreContainer and load its cores
   * @param solrHome the solr home directory
   * @param configFile the file containing this container's configuration
   * @return a loaded CoreContainer
   */
  public static CoreContainer createAndLoad(String solrHome, File configFile) {
    SolrResourceLoader loader = new SolrResourceLoader(solrHome);
    CoreContainer cc = new CoreContainer(loader, ConfigSolr.fromFile(loader, configFile));
    cc.load();
    return cc;
  }
  
  public Properties getContainerProperties() {
    return containerProperties;
  }

  //-------------------------------------------------------------------
  // Initialization / Cleanup
  //-------------------------------------------------------------------

  /**
   * Load the cores defined for this CoreContainer
   */
  public void load()  {

    log.info("Loading cores into CoreContainer [instanceDir={}]", loader.getInstanceDir());

    ThreadPoolExecutor coreLoadExecutor = null;

    // add the sharedLib to the shared resource loader before initializing cfg based plugins
    libDir = cfg.get(ConfigSolr.CfgProp.SOLR_SHAREDLIB , null);
    if (libDir != null) {
      File f = FileUtils.resolvePath(new File(solrHome), libDir);
      log.info("loading shared library: " + f.getAbsolutePath());
      loader.addToClassLoader(libDir, null, false);
      loader.reloadLuceneSPI();
    }

    shardHandlerFactory = initShardHandlerFactory();

    solrCores.allocateLazyCores(cfg, loader);

    logging = JulWatcher.newRegisteredLogWatcher(cfg, loader);

    if (cfg instanceof ConfigSolrXmlOld) { //TODO: Remove for 5.0
      String dcoreName = cfg.get(ConfigSolr.CfgProp.SOLR_CORES_DEFAULT_CORE_NAME, null);
      if (dcoreName != null && !dcoreName.isEmpty()) {
        defaultCoreName = dcoreName;
      }
      persistent = cfg.getBool(ConfigSolr.CfgProp.SOLR_PERSISTENT, false);
      adminPath = cfg.get(ConfigSolr.CfgProp.SOLR_ADMINPATH, "/admin/cores");
    } else {
      adminPath = "/admin/cores";
      defaultCoreName = DEFAULT_DEFAULT_CORE_NAME;
    }
    zkHost = cfg.get(ConfigSolr.CfgProp.SOLR_ZKHOST, System.getProperty("zkHost"));
    coreLoadThreads = cfg.getInt(ConfigSolr.CfgProp.SOLR_CORELOADTHREADS, CORE_LOAD_THREADS);
    

    shareSchema = cfg.getBool(ConfigSolr.CfgProp.SOLR_SHARESCHEMA, DEFAULT_SHARE_SCHEMA);
    zkClientTimeout = cfg.getInt(ConfigSolr.CfgProp.SOLR_ZKCLIENTTIMEOUT, DEFAULT_ZK_CLIENT_TIMEOUT);
    
    distribUpdateConnTimeout = cfg.getInt(ConfigSolr.CfgProp.SOLR_DISTRIBUPDATECONNTIMEOUT, 0);
    distribUpdateSoTimeout = cfg.getInt(ConfigSolr.CfgProp.SOLR_DISTRIBUPDATESOTIMEOUT, 0);

    // Note: initZooKeeper will apply hardcoded default if cloud mode
    hostPort = cfg.get(ConfigSolr.CfgProp.SOLR_HOSTPORT, System.getProperty("hostPort"));
    // Note: initZooKeeper will apply hardcoded default if cloud mode
    String hostContext = cfg.get(ConfigSolr.CfgProp.SOLR_HOSTCONTEXT, null);

    String host = cfg.get(ConfigSolr.CfgProp.SOLR_HOST, null);
    
    String leaderVoteWait = cfg.get(ConfigSolr.CfgProp.SOLR_LEADERVOTEWAIT, LEADER_VOTE_WAIT);

    adminHandler = initAdminHandler();
    managementPath = cfg.get(ConfigSolr.CfgProp.SOLR_MANAGEMENTPATH, null);

    transientCacheSize = cfg.getInt(ConfigSolr.CfgProp.SOLR_TRANSIENTCACHESIZE, Integer.MAX_VALUE);

    boolean genericCoreNodeNames = cfg.getBool(ConfigSolr.CfgProp.SOLR_GENERICCORENODENAMES, false);

    if (shareSchema) {
      indexSchemaCache = new ConcurrentHashMap<String,IndexSchema>();
    }

    zkClientTimeout = Integer.parseInt(System.getProperty("zkClientTimeout",
        Integer.toString(zkClientTimeout)));
    zkSys.initZooKeeper(this, solrHome, zkHost, zkClientTimeout, hostPort, hostContext, host, leaderVoteWait, genericCoreNodeNames, distribUpdateConnTimeout, distribUpdateSoTimeout);
    
    if (isZooKeeperAware() && coreLoadThreads <= 1) {
      throw new SolrException(ErrorCode.SERVER_ERROR,
          "SolrCloud requires a value of at least 2 in solr.xml for coreLoadThreads");
    }
    
    if (adminPath != null) {
      if (adminHandler == null) {
        coreAdminHandler = new CoreAdminHandler(this);
      } else {
        coreAdminHandler = this.createMultiCoreHandler(adminHandler);
      }
    }
    
    collectionsHandler = new CollectionsHandler(this);
    containerProperties = cfg.getSolrProperties("solr");

    // setup executor to load cores in parallel
    coreLoadExecutor = new ThreadPoolExecutor(coreLoadThreads, coreLoadThreads, 1,
        TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
        new DefaultSolrThreadFactory("coreLoadExecutor"));
    try {
      CompletionService<SolrCore> completionService = new ExecutorCompletionService<SolrCore>(
          coreLoadExecutor);
      Set<Future<SolrCore>> pending = new HashSet<Future<SolrCore>>();

      List<String> allCores = cfg.getAllCoreNames();

      for (String oneCoreName : allCores) {

        try {
          String rawName = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_NAME, null);

          if (null == rawName) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                "Each core in solr.xml must have a 'name'");
          }
          final String name = rawName;
          final CoreDescriptor p = new CoreDescriptor(this, name,
              cfg.getProperty(oneCoreName, CoreDescriptor.CORE_INSTDIR, null));
          
          // deal with optional settings
          String opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_CONFIG, null);
          
          if (opt != null) {
            p.setConfigName(opt);
          }
          opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_SCHEMA, null);
          if (opt != null) {
            p.setSchemaName(opt);
          }
          
          if (zkSys.getZkController() != null) {
            opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_SHARD, null);
            if (opt != null && opt.length() > 0) {
              p.getCloudDescriptor().setShardId(opt);
            }
            opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_COLLECTION, null);
            if (opt != null) {
              p.getCloudDescriptor().setCollectionName(opt);
            }
            opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_ROLES, null);
            if (opt != null) {
              p.getCloudDescriptor().setRoles(opt);
            }

            opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_NODE_NAME, null);
            if (opt != null && opt.length() > 0) {
              p.getCloudDescriptor().setCoreNodeName(opt);
            }
          }
          opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_PROPERTIES, null);
          if (opt != null) {
            p.setPropertiesName(opt);
          }
          opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_DATADIR, null);
          if (opt != null) {
            p.setDataDir(opt);
          }
          
          p.setCoreProperties(cfg.readCoreProperties(oneCoreName));
          
          opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_LOADONSTARTUP, null);
          if (opt != null) {
            p.setLoadOnStartup(("true".equalsIgnoreCase(opt) || "on"
                .equalsIgnoreCase(opt)) ? true : false);
          }
          
          opt = cfg.getProperty(oneCoreName, CoreDescriptor.CORE_TRANSIENT, null);
          if (opt != null) {
            p.setTransient(("true".equalsIgnoreCase(opt) || "on"
                .equalsIgnoreCase(opt)) ? true : false);
          }

          if (p.isTransient() || ! p.isLoadOnStartup()) {
            // Store it away for later use. includes non-transient but not
            // loaded at startup cores.
            solrCores.putDynamicDescriptor(rawName, p);
          }

          if (p.isLoadOnStartup()) { // The normal case

            doStartupCoreCreation(completionService, pending, name, p);

          }
        } catch (Throwable ex) {
          SolrException.log(log, null, ex);
        }
      }
      
      while (pending != null && pending.size() > 0) {
        try {

          Future<SolrCore> future = completionService.take();
          if (future == null) return;
          pending.remove(future);

          try {
            SolrCore c = future.get();
            // track original names
            if (c != null) {
              solrCores.putCoreToOrigName(c, c.getName());
            }
          } catch (ExecutionException e) {
            SolrException.log(SolrCore.log, "Error loading core", e);
          }
          
        } catch (InterruptedException e) {
          throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE,
              "interrupted while loading core", e);
        }
      }

      // Start the background thread
      backgroundCloser = new CloserThread(this, solrCores, cfg);
      backgroundCloser.start();

    } finally {
      if (coreLoadExecutor != null) {
        ExecutorUtil.shutdownNowAndAwaitTermination(coreLoadExecutor);
      }
    }
  }

  protected void doStartupCoreCreation(CompletionService<SolrCore> completionService, Set<Future<SolrCore>> pending, final String name, final CoreDescriptor coreDescriptor) {
    Callable<SolrCore> task = new Callable<SolrCore>() {
      @Override
      public SolrCore call() {
        SolrCore c = null;
        try {
          if (zkSys.getZkController() != null) {
            preRegisterInZk(coreDescriptor);
          }
          c = create(coreDescriptor);
          registerCore(coreDescriptor.isTransient(), name, c, false);
        } catch (Throwable t) {
          if (isZooKeeperAware()) {
            try {
              zkSys.zkController.unregister(name, coreDescriptor);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              SolrException.log(log, null, e);
            } catch (KeeperException e) {
              SolrException.log(log, null, e);
            }
          }
          SolrException.log(log, null, t);
          if (c != null) {
            c.close();
          }
        }
        return c;
      }
    };
    pending.add(completionService.submit(task));
  }

  private volatile boolean isShutDown = false;
  
  public boolean isShutDown() {
    return isShutDown;
  }

  /**
   * Stops all cores.
   */
  public void shutdown() {
    log.info("Shutting down CoreContainer instance="
        + System.identityHashCode(this));
    
    if (isZooKeeperAware()) {
      try {
        zkSys.getZkController().publishAndWaitForDownStates();
      } catch (KeeperException e) {
        log.error("", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("", e);
      }
    }
    isShutDown = true;

    if (isZooKeeperAware()) {
      zkSys.publishCoresAsDown(solrCores.getCores());
      cancelCoreRecoveries();
    }


    try {
      // First wake up the closer thread, it'll terminate almost immediately since it checks isShutDown.
      synchronized (solrCores.getModifyLock()) {
        solrCores.getModifyLock().notifyAll(); // wake up anyone waiting
      }
      if (backgroundCloser != null) { // Doesn't seem right, but tests get in here without initializing the core.
        try {
          backgroundCloser.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          if (log.isDebugEnabled()) {
            log.debug("backgroundCloser thread was interrupted before finishing");
          }
        }
      }
      // Now clear all the cores that are being operated upon.
      solrCores.close();

      // It's still possible that one of the pending dynamic load operation is waiting, so wake it up if so.
      // Since all the pending operations queues have been drained, there should be nothing to do.
      synchronized (solrCores.getModifyLock()) {
        solrCores.getModifyLock().notifyAll(); // wake up the thread
      }

    } finally {
      if (shardHandlerFactory != null) {
        shardHandlerFactory.close();
      }
      
      // we want to close zk stuff last

      zkSys.close();

    }
    org.apache.lucene.util.IOUtils.closeWhileHandlingException(loader); // best effort
  }

  public void cancelCoreRecoveries() {

    List<SolrCore> cores = solrCores.getCores();

    // we must cancel without holding the cores sync
    // make sure we wait for any recoveries to stop
    for (SolrCore core : cores) {
      try {
        core.getSolrCoreState().cancelRecovery();
      } catch (Throwable t) {
        SolrException.log(log, "Error canceling recovery for core", t);
      }
    }
  }
  
  @Override
  protected void finalize() throws Throwable {
    try {
      if(!isShutDown){
        log.error("CoreContainer was not shutdown prior to finalize(), indicates a bug -- POSSIBLE RESOURCE LEAK!!!  instance=" + System.identityHashCode(this));
      }
    } finally {
      super.finalize();
    }
  }

  protected SolrCore registerCore(boolean isTransientCore, String name, SolrCore core, boolean returnPrevNotClosed) {
    if( core == null ) {
      throw new RuntimeException( "Can not register a null core." );
    }
    if( name == null ||
        name.indexOf( '/'  ) >= 0 ||
        name.indexOf( '\\' ) >= 0 ){
      throw new RuntimeException( "Invalid core name: "+name );
    }
    
    SolrCore old = null;

    if (isShutDown) {
      core.close();
      throw new IllegalStateException("This CoreContainer has been shutdown");
    }
    if (isTransientCore) {
      old = solrCores.putTransientCore(cfg, name, core, loader);
    } else {
      old = solrCores.putCore(name, core);
    }
      /*
      * set both the name of the descriptor and the name of the
      * core, since the descriptors name is used for persisting.
      */

    core.setName(name);
    core.getCoreDescriptor().putProperty(CoreDescriptor.CORE_NAME, name);

    synchronized (coreInitFailures) {
      coreInitFailures.remove(name);
    }

    if( old == null || old == core) {
      log.info( "registering core: "+name );
      zkSys.registerInZk(core);
      return null;
    }
    else {
      log.info( "replacing core: "+name );
      if (!returnPrevNotClosed) {
        old.close();
      }
      zkSys.registerInZk(core);
      return old;
    }
  }

  /**
   * Registers a SolrCore descriptor in the registry using the core's name.
   * If returnPrev==false, the old core, if different, is closed.
   * @return a previous core having the same name if it existed and returnPrev==true
   */
  public SolrCore register(SolrCore core, boolean returnPrev) {
    return registerCore(false, core.getName(), core, returnPrev);
  }

  public SolrCore register(String name, SolrCore core, boolean returnPrev) {
    return registerCore(false, name, core, returnPrev);
  }

  // Helper method to separate out creating a core from local configuration files. See create()
  private SolrCore createFromLocal(String instanceDir, CoreDescriptor dcore) {
    SolrResourceLoader solrLoader = null;

    SolrConfig config = null;
    solrLoader = new SolrResourceLoader(instanceDir, loader.getClassLoader(), ConfigSolrXml.getCoreProperties(instanceDir, dcore));
    try {
      config = new SolrConfig(solrLoader, dcore.getConfigName(), null);
    } catch (Exception e) {
      log.error("Failed to load file {}", new File(instanceDir, dcore.getConfigName()).getAbsolutePath());
      throw new SolrException(ErrorCode.SERVER_ERROR, "Could not load config for " + dcore.getConfigName(), e);
    }

    IndexSchema schema = null;
    if (indexSchemaCache != null) {
      final String resourceNameToBeUsed = IndexSchemaFactory.getResourceNameToBeUsed(dcore.getSchemaName(), config);
      File schemaFile = new File(resourceNameToBeUsed);
      if (!schemaFile.isAbsolute()) {
        schemaFile = new File(solrLoader.getConfigDir(), schemaFile.getPath());
      }
      if (schemaFile.exists()) {
        String key = schemaFile.getAbsolutePath()
            + ":"
            + new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date(
            schemaFile.lastModified()));
        schema = indexSchemaCache.get(key);
        if (schema == null) {
          log.info("creating new schema object for core: " + dcore.getProperty(CoreDescriptor.CORE_NAME));
          schema = IndexSchemaFactory.buildIndexSchema(dcore.getSchemaName(), config);
          indexSchemaCache.put(key, schema);
        } else {
          log.info("re-using schema object for core: " + dcore.getProperty(CoreDescriptor.CORE_NAME));
        }
      }
    }

    if (schema == null) {
      schema = IndexSchemaFactory.buildIndexSchema(dcore.getSchemaName(), config);
    }

    SolrCore core = new SolrCore(dcore.getName(), null, config, schema, dcore);

    if (core.getUpdateHandler().getUpdateLog() != null) {
      // always kick off recovery if we are in standalone mode.
      core.getUpdateHandler().getUpdateLog().recoverFromLog();
    }
    return core;
  }

  /**
   * Creates a new core based on a descriptor but does not register it.
   *
   * @param dcore a core descriptor
   * @return the newly created core
   */
  public SolrCore create(CoreDescriptor dcore) {

    if (isShutDown) {
      throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE, "Solr has shutdown.");
    }
    
    final String name = dcore.getName();

    try {
      // Make the instanceDir relative to the cores instanceDir if not absolute
      File idir = new File(dcore.getInstanceDir());
      String instanceDir = idir.getPath();
      log.info("Creating SolrCore '{}' using instanceDir: {}",
               dcore.getName(), instanceDir);

      // Initialize the solr config
      SolrCore created = null;
      if (zkSys.getZkController() != null) {
        created = zkSys.createFromZk(instanceDir, dcore, loader);
      } else {
        created = createFromLocal(instanceDir, dcore);
      }

      solrCores.addCreated(created); // For persisting newly-created cores.
      return created;

      // :TODO: Java7...
      // http://docs.oracle.com/javase/7/docs/technotes/guides/language/catch-multiple.html
    } catch (Exception ex) {
      throw recordAndThrow(name, "Unable to create core: " + name, ex);
    }
  }

  /**
   * @return a Collection of registered SolrCores
   */
  public Collection<SolrCore> getCores() {
    return solrCores.getCores();
  }

  /**
   * @return a Collection of the names that cores are mapped to
   */
  public Collection<String> getCoreNames() {
    return solrCores.getCoreNames();
  }

  /** This method is currently experimental.
   * @return a Collection of the names that a specific core is mapped to.
   */
  public Collection<String> getCoreNames(SolrCore core) {
    return solrCores.getCoreNames(core);
  }

  /**
   * get a list of all the cores that are currently loaded
   * @return a list of al lthe available core names in either permanent or transient core lists.
   */
  public Collection<String> getAllCoreNames() {
    return solrCores.getAllCoreNames();

  }

  /**
   * Returns an immutable Map of Exceptions that occured when initializing 
   * SolrCores (either at startup, or do to runtime requests to create cores) 
   * keyed off of the name (String) of the SolrCore that had the Exception 
   * during initialization.
   * <p>
   * While the Map returned by this method is immutable and will not change 
   * once returned to the client, the source data used to generate this Map 
   * can be changed as various SolrCore operations are performed:
   * </p>
   * <ul>
   *  <li>Failed attempts to create new SolrCores will add new Exceptions.</li>
   *  <li>Failed attempts to re-create a SolrCore using a name already contained in this Map will replace the Exception.</li>
   *  <li>Failed attempts to reload a SolrCore will cause an Exception to be added to this list -- even though the existing SolrCore with that name will continue to be available.</li>
   *  <li>Successful attempts to re-created a SolrCore using a name already contained in this Map will remove the Exception.</li>
   *  <li>Registering an existing SolrCore with a name already contained in this Map (ie: ALIAS or SWAP) will remove the Exception.</li>
   * </ul>
   */
  public Map<String,Exception> getCoreInitFailures() {
    synchronized ( coreInitFailures ) {
      return Collections.unmodifiableMap(new LinkedHashMap<String,Exception>
                                         (coreInitFailures));
    }
  }


  // ---------------- Core name related methods --------------- 
  /**
   * Recreates a SolrCore.
   * While the new core is loading, requests will continue to be dispatched to
   * and processed by the old core
   * 
   * @param name the name of the SolrCore to reload
   */
  public void reload(String name) {
    try {
      name = checkDefault(name);

      SolrCore core = solrCores.getCoreFromAnyList(name, false);
      if (core == null)
        throw new SolrException( SolrException.ErrorCode.BAD_REQUEST, "No such core: " + name );

      try {
        solrCores.waitAddPendingCoreOps(name);
        CoreDescriptor cd = core.getCoreDescriptor();

        File instanceDir = new File(cd.getInstanceDir());

        log.info("Reloading SolrCore '{}' using instanceDir: {}",
                 cd.getName(), instanceDir.getAbsolutePath());
        SolrResourceLoader solrLoader;
        if(zkSys.getZkController() == null) {
          solrLoader = new SolrResourceLoader(instanceDir.getAbsolutePath(), loader.getClassLoader(), ConfigSolrXml.getCoreProperties(instanceDir.getAbsolutePath(), cd));
        } else {
          try {
            String collection = cd.getCloudDescriptor().getCollectionName();
            zkSys.getZkController().createCollectionZkNode(cd.getCloudDescriptor());

            String zkConfigName = zkSys.getZkController().readConfigName(collection);
            if (zkConfigName == null) {
              log.error("Could not find config name for collection:" + collection);
              throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
                                           "Could not find config name for collection:" + collection);
            }
            solrLoader = new ZkSolrResourceLoader(instanceDir.getAbsolutePath(), zkConfigName, loader.getClassLoader(),
                ConfigSolrXml.getCoreProperties(instanceDir.getAbsolutePath(), cd), zkSys.getZkController());
          } catch (KeeperException e) {
            log.error("", e);
            throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
                                         "", e);
          } catch (InterruptedException e) {
            // Restore the interrupted status
            Thread.currentThread().interrupt();
            log.error("", e);
            throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
                                         "", e);
          }
        }
        SolrCore newCore = core.reload(solrLoader, core);
        // keep core to orig name link
        solrCores.removeCoreToOrigName(newCore, core);
        registerCore(false, name, newCore, false);
      } finally {
        solrCores.removeFromPendingOps(name);
      }
      // :TODO: Java7...
      // http://docs.oracle.com/javase/7/docs/technotes/guides/language/catch-multiple.html
    } catch (Exception ex) {
      throw recordAndThrow(name, "Unable to reload core: " + name, ex);
    }
  }

  //5.0 remove all checkDefaults?
  private String checkDefault(String name) {
    return (null == name || name.isEmpty()) ? defaultCoreName : name;
  } 

  /**
   * Swaps two SolrCore descriptors.
   */
  public void swap(String n0, String n1) {
    if( n0 == null || n1 == null ) {
      throw new SolrException( SolrException.ErrorCode.BAD_REQUEST, "Can not swap unnamed cores." );
    }
    n0 = checkDefault(n0);
    n1 = checkDefault(n1);
    solrCores.swap(n0, n1);

    log.info("swapped: "+n0 + " with " + n1);
  }
  
  /** Removes and returns registered core w/o decrementing it's reference count */
  public SolrCore remove( String name ) {
    name = checkDefault(name);    

    return solrCores.remove(name, true);

  }

  public void rename(String name, String toName) {
    SolrCore core = getCore(name);
    try {
      if (core != null) {
        registerCore(false, toName, core, false);
        name = checkDefault(name);
        solrCores.remove(name, false);
      }
    } finally {
      if (core != null) {
        core.close();
      }
    }
  }
  /** 
   * Gets a core by name and increase its refcount.
   *
   * @see SolrCore#close() 
   * @param name the core name
   * @return the core if found, null if a SolrCore by this name does not exist
   * @exception SolrException if a SolrCore with this name failed to be initialized
   */
  public SolrCore getCore(String name) {

    name = checkDefault(name);

    // Do this in two phases since we don't want to lock access to the cores over a load.
    SolrCore core = solrCores.getCoreFromAnyList(name, true);

    if (core != null) {
      return core;
    }

    // OK, it's not presently in any list, is it in the list of dynamic cores but not loaded yet? If so, load it.
    CoreDescriptor desc = solrCores.getDynamicDescriptor(name);
    if (desc == null) { //Nope, no transient core with this name
      
      // if there was an error initalizing this core, throw a 500
      // error with the details for clients attempting to access it.
      Exception e = getCoreInitFailures().get(name);
      if (null != e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "SolrCore '" + name +
                                "' is not available due to init failure: " +
                                e.getMessage(), e);
      }
      // otherwise the user is simply asking for something that doesn't exist.
      return null;
    }

    // This will put an entry in pending core ops if the core isn't loaded
    core = solrCores.waitAddPendingCoreOps(name);

    if (isShutDown) return null; // We're quitting, so stop. This needs to be after the wait above since we may come off
                                 // the wait as a consequence of shutting down.
    try {
      if (core == null) {
        if (zkSys.getZkController() != null) {
          preRegisterInZk(desc);
        }
        core = create(desc); // This should throw an error if it fails.
        core.open();
        registerCore(desc.isTransient(), name, core, false);
      } else {
        core.open();
      }
    } catch(Exception ex){
      // remains to be seen how transient cores and such
      // will work in SolrCloud mode, but just to be future 
      // proof...
      if (isZooKeeperAware()) {
        try {
          getZkController().unregister(name, desc);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          SolrException.log(log, null, e);
        } catch (KeeperException e) {
          SolrException.log(log, null, e);
        }
      }
      throw recordAndThrow(name, "Unable to create core: " + name, ex);
    } finally {
      solrCores.removeFromPendingOps(name);
    }

    return core;
  }

  // ---------------- Multicore self related methods ---------------
  /** 
   * Creates a CoreAdminHandler for this MultiCore.
   * @return a CoreAdminHandler
   */
  protected CoreAdminHandler createMultiCoreHandler(final String adminHandlerClass) {
    return loader.newAdminHandlerInstance(CoreContainer.this, adminHandlerClass);
  }

  public CoreAdminHandler getMultiCoreHandler() {
    return coreAdminHandler;
  }
  
  public CollectionsHandler getCollectionsHandler() {
    return collectionsHandler;
  }
  
  /**
   * the default core name, or null if there is no default core name
   */
  public String getDefaultCoreName() {
    return defaultCoreName;
  }
  
  // all of the following properties aren't synchronized
  // but this should be OK since they normally won't be changed rapidly
  @Deprecated
  public boolean isPersistent() {
    return persistent;
  }
  
  @Deprecated
  public void setPersistent(boolean persistent) {
    this.persistent = persistent;
  }
  
  public String getAdminPath() {
    return adminPath;
  }

  public String getManagementPath() {
    return managementPath;
  }

  public String getHostPort() {
    return hostPort;
  }

  protected ShardHandlerFactory initShardHandlerFactory() {
    return ShardHandlerFactory.newInstance(cfg.getShardHandlerFactoryPluginInfo(), loader);
  }

  protected String initAdminHandler() {
    return cfg.get(ConfigSolr.CfgProp.SOLR_ADMINHANDLER, null);
  }

  /**
   * Sets the alternate path for multicore handling:
   * This is used in case there is a registered unnamed core (aka name is "") to
   * declare an alternate way of accessing named cores.
   * This can also be used in a pseudo single-core environment so admins can prepare
   * a new version before swapping.
   */
  public void setManagementPath(String path) {
    this.managementPath = path;
  }
  
  public LogWatcher getLogging() {
    return logging;
  }
  public void setLogging(LogWatcher v) {
    logging = v;
  }
  
  public File getConfigFile() {
    return new File(solrHome, ConfigSolr.SOLR_XML_FILE);
  }

  /**
   * Determines whether the core is already loaded or not but does NOT load the core
   *
   */
  public boolean isLoaded(String name) {
    return solrCores.isLoaded(name);
  }

  /** Persists the cores config file in cores.xml. */
  @Deprecated
  public void persist() {
    persistFile(getConfigFile());
  }

  /**
   * Gets a solr core descriptor for a core that is not loaded. Note that if the caller calls this on a
   * loaded core, the unloaded descriptor will be returned.
   *
   * @param cname - name of the unloaded core descriptor to load. NOTE:
   * @return a coreDescriptor. May return null
   */
  public CoreDescriptor getUnloadedCoreDescriptor(String cname) {
    return solrCores.getUnloadedCoreDescriptor(cname);
  }

  /** Persists the cores config file in a user provided file. */
  @Deprecated
  public void persistFile(File file) {
    assert file != null;
    // only the old solrxml persists
    if (cfg != null && !(cfg instanceof ConfigSolrXmlOld)) return;

    log.info("Persisting cores config to " + (file == null ? getConfigFile() : file));

    // <solr attrib="value">
    Map<String,String> rootSolrAttribs = new HashMap<String,String>();

    addAttrib(rootSolrAttribs, ConfigSolr.CfgProp.SOLR_SHAREDLIB, "sharedLib", this.libDir);
    addAttrib(rootSolrAttribs, ConfigSolr.CfgProp.SOLR_PERSISTENT, "persistent",
        Boolean.toString(isPersistent()), "false");
    addAttrib(rootSolrAttribs, ConfigSolr.CfgProp.SOLR_CORELOADTHREADS, "coreLoadThreads",
        Integer.toString(this.coreLoadThreads), Integer.toString(CORE_LOAD_THREADS));
    addAttrib(rootSolrAttribs, ConfigSolr.CfgProp.SOLR_ZKHOST, "zkHost", this.zkHost);

    // <solr attrib="value"> <cores attrib="value">
    Map<String,String> coresAttribs = new HashMap<String,String>();
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_ADMINPATH, "adminPath", this.adminPath, this.getAdminPath());
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_ADMINHANDLER, "adminHandler", this.adminHandler);
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_SHARESCHEMA, "shareSchema",
        Boolean.toString(this.shareSchema),
        Boolean.toString(DEFAULT_SHARE_SCHEMA));
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_HOST, "host", zkSys.getHost());

    if (! (null == defaultCoreName || defaultCoreName.equals("")) ) {
      coresAttribs.put("defaultCoreName", defaultCoreName);
    }

    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_HOSTPORT, "hostPort", zkSys.getHostPort());

    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_ZKCLIENTTIMEOUT, "zkClientTimeout",
        intToString(this.zkClientTimeout),
        Integer.toString(DEFAULT_ZK_CLIENT_TIMEOUT));
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_HOSTCONTEXT, "hostContext",
        zkSys.getHostContext());
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_LEADERVOTEWAIT, "leaderVoteWait",
        zkSys.getLeaderVoteWait(), LEADER_VOTE_WAIT);
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_GENERICCORENODENAMES, "genericCoreNodeNames",
        Boolean.toString(zkSys.getGenericCoreNodeNames()), "false");
    if (transientCacheSize != Integer.MAX_VALUE) { // This test
    // is a consequence of testing. I really hate it.
      addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_TRANSIENTCACHESIZE, "transientCacheSize",
          Integer.toString(this.transientCacheSize), Integer.toString(Integer.MAX_VALUE));
    }
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_DISTRIBUPDATECONNTIMEOUT, "distribUpdateConnTimeout",
        Integer.toString(this.distribUpdateConnTimeout), Integer.toString(this.distribUpdateConnTimeout));
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_DISTRIBUPDATESOTIMEOUT, "distribUpdateSoTimeout",
        Integer.toString(this.distribUpdateSoTimeout), Integer.toString(this.distribUpdateSoTimeout));
    addAttrib(coresAttribs, ConfigSolr.CfgProp.SOLR_MANAGEMENTPATH, "managementPath",
        this.managementPath);

    // don't forget the logging stuff
    Map<String, String> loggingAttribs = new HashMap<String, String>();
    addAttrib(loggingAttribs, ConfigSolr.CfgProp.SOLR_LOGGING_CLASS, "class",
        cfg.get(ConfigSolr.CfgProp.SOLR_LOGGING_CLASS, null));
    addAttrib(loggingAttribs, ConfigSolr.CfgProp.SOLR_LOGGING_ENABLED, "enabled",
        cfg.get(ConfigSolr.CfgProp.SOLR_LOGGING_ENABLED, null));

    Map<String, String> watcherAttribs = new HashMap<String, String>();
    addAttrib(watcherAttribs, ConfigSolr.CfgProp.SOLR_LOGGING_WATCHER_SIZE, "size",
        cfg.get(ConfigSolr.CfgProp.SOLR_LOGGING_WATCHER_SIZE, null));
    addAttrib(watcherAttribs, ConfigSolr.CfgProp.SOLR_LOGGING_WATCHER_THRESHOLD, "threshold",
        cfg.get(ConfigSolr.CfgProp.SOLR_LOGGING_WATCHER_THRESHOLD, null));


    /*
    Map<String, String> shardHandlerAttrib = new HashMap<String, String>();
    addAttrib(shardHandlerAttrib, ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_CLASS, "class",
        cfg.get(ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_CLASS, null));
    addAttrib(shardHandlerAttrib, ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_NAME, "name",
        cfg.get(ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_NAME, null));

    Map<String, String> shardHandlerProps = new HashMap<String, String>();
    addAttrib(shardHandlerProps, ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_CONNTIMEOUT, "connTimeout",
        cfg.get(ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_CONNTIMEOUT, null));
    addAttrib(shardHandlerProps, ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_SOCKETTIMEOUT, "socketTimeout",
        cfg.get(ConfigSolr.CfgProp.SOLR_SHARDHANDLERFACTORY_SOCKETTIMEOUT, null));
    */

    try {
      solrCores.persistCores(cfg.config.getOriginalConfig(), containerProperties, rootSolrAttribs,coresAttribs,
          loggingAttribs, watcherAttribs, cfg.getUnsubsititutedShardHandlerFactoryPluginNode(), file, loader);
    } catch (XPathExpressionException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, null, e);
    }

  }
  private String intToString(Integer integer) {
    if (integer == null) return null;
    return Integer.toString(integer);
  }

  private void addAttrib(Map<String, String> attribs, ConfigSolr.CfgProp prop,
                         String attribName, String attribValue) {
    addAttrib(attribs, prop, attribName, attribValue, null);
  }

    private void addAttrib(Map<String, String> attribs, ConfigSolr.CfgProp prop,
                         String attribName, String attribValue, String defaultValue) {
    if (cfg == null) {
      attribs.put(attribName, attribValue);
      return;
    }

    if (attribValue != null) {
      String origValue = cfg.getOrigProp(prop, null);

      if (origValue == null && defaultValue != null && attribValue.equals(defaultValue)) return;

      if (attribValue.equals(PropertiesUtil.substituteProperty(origValue, loader.getCoreProperties()))) {
        attribs.put(attribName, origValue);
      } else {
        attribs.put(attribName, attribValue);
      }
    }
  }

  public void preRegisterInZk(final CoreDescriptor p) {
    zkSys.getZkController().preRegister(p);
  }

  public String getSolrHome() {
    return solrHome;
  }

  public boolean isZooKeeperAware() {
    return zkSys.getZkController() != null;
  }
  
  public ZkController getZkController() {
    return zkSys.getZkController();
  }
  
  public boolean isShareSchema() {
    return shareSchema;
  }

  /** The default ShardHandlerFactory used to communicate with other solr instances */
  public ShardHandlerFactory getShardHandlerFactory() {
    return shardHandlerFactory;
  }
  
  // Just to tidy up the code where it did this in-line.
  private SolrException recordAndThrow(String name, String msg, Exception ex) {
    synchronized (coreInitFailures) {
      coreInitFailures.remove(name);
      coreInitFailures.put(name, ex);
    }
    log.error(msg, ex);
    return new SolrException(ErrorCode.SERVER_ERROR, msg, ex);
  }
  
  String getCoreToOrigName(SolrCore core) {
    return solrCores.getCoreToOrigName(core);
  }
  

}

class CloserThread extends Thread {
  CoreContainer container;
  SolrCores solrCores;
  ConfigSolr cfg;


  CloserThread(CoreContainer container, SolrCores solrCores, ConfigSolr cfg) {
    this.container = container;
    this.solrCores = solrCores;
    this.cfg = cfg;
  }

  // It's important that this be the _only_ thread removing things from pendingDynamicCloses!
  // This is single-threaded, but I tried a multi-threaded approach and didn't see any performance gains, so
  // there's no good justification for the complexity. I suspect that the locking on things like DefaultSolrCoreState
  // essentially create a single-threaded process anyway.
  @Override
  public void run() {
    while (! container.isShutDown()) {
      synchronized (solrCores.getModifyLock()) { // need this so we can wait and be awoken.
        try {
          solrCores.getModifyLock().wait();
        } catch (InterruptedException e) {
          // Well, if we've been told to stop, we will. Otherwise, continue on and check to see if there are
          // any cores to close.
        }
      }
      for (SolrCore removeMe = solrCores.getCoreToClose();
           removeMe != null && !container.isShutDown();
           removeMe = solrCores.getCoreToClose()) {
        try {
          removeMe.close();
        } finally {
          solrCores.removeFromPendingOps(removeMe.getName());
        }
      }
    }
  }
  
}
