/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.installer.provider.file.impl;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;

import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.api.OsgiInstaller;
import org.apache.sling.installer.api.UpdateHandler;
import org.apache.sling.installer.api.UpdateResult;
import org.apache.sling.installer.api.serializer.ConfigurationSerializer;
import org.apache.sling.installer.api.serializer.ConfigurationSerializerFactory;
import org.apache.sling.settings.SlingSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>FileInstaller</code> manages the file installers and
 * handles updates.
 *
 */
public class FileInstaller
    implements UpdateHandler {

    private static final String CONFIG_FILE_EXTENSION = ".cfg.json";

    /** The scheme we use to register our resources. */
    public static final String SCHEME_PREFIX = "fileinstall";

    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /** All active scan configurations. */
    private final List<ScanConfiguration> scanConfigurations = new ArrayList<ScanConfiguration>();

    /** All monitors. */
    private final List<FileMonitor> monitors = new ArrayList<FileMonitor>();

    private final boolean writeBack;
    private final boolean autoCreateDirectories;

    public FileInstaller(final List<ScanConfiguration> configs, final boolean writeBack, boolean autoCreateDirectories) {
        this.writeBack = writeBack;
        this.autoCreateDirectories = autoCreateDirectories;
        if ( configs != null ) {
            scanConfigurations.addAll(configs);
        }
    }

    public boolean hasConfigurations() {
        return !this.scanConfigurations.isEmpty();
    }

    public void start(final OsgiInstaller installer, final SlingSettingsService settings) {
        for(final ScanConfiguration config : this.scanConfigurations) {
            String key = config.directory;
            if ( key.startsWith(settings.getSlingHomePath() + File.separator) ) {
                key = "${sling.home}" + key.substring(settings.getSlingHomePath().length());
            }
            logger.debug("Starting monitor for {}", config.directory);
            if (autoCreateDirectories) {
                try {
                    Files.createDirectories(Paths.get(config.directory));
                } catch (IOException e) {
                    logger.warn("Could not create directory to monitor at {}", config.directory, e);
                }
            }
            this.monitors.add(new FileMonitor(new File(config.directory),
                    config.scanInterval, new Installer(installer, settings, config.directory, hash(key))));
        }
    }

    public void stop() {
        for(final FileMonitor monitor : this.monitors) {
            monitor.stop();
        }
        this.monitors.clear();

    }

    public String[] getSchemes() {
        final String[] schemes = new String[this.monitors.size()];
        int index = 0;

        for(final FileMonitor m : this.monitors) {
            schemes[index] = m.getListener().getScheme();
            index++;
        }

        return schemes;
    }

    /**
     * @see org.apache.sling.installer.api.UpdateHandler#handleRemoval(java.lang.String, java.lang.String, java.lang.String)
     */
    public UpdateResult handleRemoval(final String resourceType,
            final String id,
            final String url) {
        if ( !this.writeBack ) {
            return null;
        }
        final int pos = url.indexOf(':');
        final String path = url.substring(pos + 1);
        // remove
        logger.debug("Removal of {}", path);
        final File file = new File(path);
        if ( file.exists() ) {
            file.delete();
        }
        return new UpdateResult(url);
    }

    /**
     * @see org.apache.sling.installer.api.UpdateHandler#handleUpdate(java.lang.String, java.lang.String, java.lang.String, java.util.Dictionary, Map)
     */
    public UpdateResult handleUpdate(final String resourceType,
            final String id,
            final String url,
            final Dictionary<String, Object> dict,
            final Map<String, Object> attributes) {
        return this.handleUpdate(resourceType, id, url, null, dict, attributes);
    }

    /**
     * @see org.apache.sling.installer.api.UpdateHandler#handleUpdate(java.lang.String, java.lang.String, java.lang.String, java.io.InputStream, Map)
     */
    public UpdateResult handleUpdate(final String resourceType,
            final String id,
            final String url,
            final InputStream is,
            final Map<String, Object> attributes) {
        return this.handleUpdate(resourceType, id, url, is, null, attributes);
    }

    /**
     * Internal implementation of update handling
     */
    private UpdateResult handleUpdate(final String resourceType,
            final String id,
            final String url,
            final InputStream is,
            final Dictionary<String, Object> dict,
            final Map<String, Object> attributes) {
        if ( !this.writeBack ) {
            return null;
        }

        // we only handle add/update of configs for now
        if ( !resourceType.equals(InstallableResource.TYPE_CONFIG) ) {
            return null;
        }

        try {
            final String path;
            final String prefix;
            if ( url != null ) {
                // update
                final int pos = url.indexOf(':');
                final String oldPath = url.substring(pos + 1);
                prefix = url.substring(0, pos);
                // ensure extension '.cfg.json'
                if ( !oldPath.endsWith(CONFIG_FILE_EXTENSION) ) {
                    final File file = new File(oldPath);
                    if ( file.exists() ) {
                        file.delete();
                    }
                    final int lastDot = oldPath.lastIndexOf('.');
                    final int lastSlash = oldPath.lastIndexOf('/');
                    if ( lastDot <= lastSlash ) {
                        path = oldPath + CONFIG_FILE_EXTENSION;
                    } else {
                        path = oldPath.substring(0, lastDot) + CONFIG_FILE_EXTENSION;
                    }
                } else {
                    path = oldPath;
                }
                logger.debug("Update of {} at {}", resourceType, path);
            } else {
                // add
                final FileMonitor first = this.monitors.get(0);
                path = first.getRoot().getAbsolutePath() + '/' + id + CONFIG_FILE_EXTENSION;
                prefix = first.getListener().getScheme();
                logger.debug("Add of {} at {}", resourceType, path);
            }

            final File file = new File(path);
            file.getParentFile().mkdirs();
            try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(file))) {
                // comments starting with "//"  allowed according to https://osgi.org/specification/osgi.cmpn/7.0.0/service.configurator.html#d0e131566
                fos.write("// Configuration created by Apache Sling File Installer\n".getBytes(StandardCharsets.UTF_8));
                ConfigurationSerializer serializer = ConfigurationSerializerFactory.create(ConfigurationSerializerFactory.Format.JSON);
                serializer.serialize(dict, fos);
            } 
            final UpdateResult result = new UpdateResult(prefix + ':' + path);
            result.setResourceIsMoved(true);
            return result;
        } catch (final IOException e) {
            logger.error("Unable to add/update resource " + resourceType + ':' + id, e);
            return null;
        }
    }

    /**
     * Hash the string
     */
    private static String hash(final String value) {
        try {
            final MessageDigest d = MessageDigest.getInstance("MD5");
            d.update(value.getBytes("UTF-8"));
            final BigInteger bigInt = new BigInteger(1, d.digest());
            return new String(bigInt.toString(16));
        } catch (final Exception ignore) {
            // if anything goes wrong we just return the value
            return value;
        }
    }
}
