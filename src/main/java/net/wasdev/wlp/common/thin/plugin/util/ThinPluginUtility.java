/*******************************************************************************
 * Copyright (c) 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package net.wasdev.wlp.common.thin.plugin.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ThinPluginUtility {
	public static final String SPRING_START_CLASS_HEADER = "Start-Class";
	public static final String SPRING_BOOT_CLASSES_HEADER = "Spring-Boot-Classes";
	public static final String SPRING_BOOT_LIB_HEADER = "Spring-Boot-Lib";
	public static final String SPRING_LIB_INDEX_FILE = "META-INF/spring.lib.index";
	private final File targetThinJar;
	private final File libIndexCache;
	private final boolean putLibCacheInDirectory;
	private final List<String> libEntries = new ArrayList<>();
	private final Set<String> prefixList = new HashSet<>();
	private final JarFile jf;
	private final SpringBootManifest sprMF;
	private String prefix;
	private String postfix;

	public ThinPluginUtility(File sourceFatJar, File targetThinJar, File libIndexCache, boolean putLibCacheInDirectory)
			throws IOException {
		this.targetThinJar = targetThinJar;
		this.libIndexCache = libIndexCache;
		this.putLibCacheInDirectory = putLibCacheInDirectory;
		jf = new JarFile(sourceFatJar);
		sprMF = new SpringBootManifest(jf.getManifest());
	}

	public void execute() throws IOException, NoSuchAlgorithmException {
		try {
			thin();
		} catch (IOException | NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private void thin() throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		Enumeration<JarEntry> entries = jf.entries();
		JarEntry entry;
		JarOutputStream thinJar = new JarOutputStream(new FileOutputStream(targetThinJar), jf.getManifest());
		ZipOutputStream libJar = null;
		if (!putLibCacheInDirectory) {
			libJar = new ZipOutputStream(new FileOutputStream(libIndexCache));
		}
		try {
			while (entries.hasMoreElements() && (entry = entries.nextElement()) != null) {
				if (!JarFile.MANIFEST_NAME.equals(entry.getName()) && !entry.getName().startsWith("org")) { // hack to omit spring boot loader
					storeEntry(thinJar, libJar, entry);
				}
			}
			addLibIndexFileToThinJar(thinJar);
		} finally {
			thinJar.close();
			if (libJar != null) {
				libJar.close();
			}

		}
	}
	
	private void storeEntry(JarOutputStream thinJar, ZipOutputStream libJar, JarEntry entry)
			throws IOException, NoSuchAlgorithmException {
		String path = entry.getName();
		if (entry.getName().startsWith(sprMF.springBootLib) && !entry.getName().equals(sprMF.springBootLib)) {
			storeHashedLibEntries(entry, entry.getName());
			if (!putLibCacheInDirectory && libJar != null) {
				storeLibraryInZip(libJar, entry);
			} else {
				storeLibraryInDir(entry);
			}
		}else {
			try (InputStream is = jf.getInputStream(entry)) {
				writeEntry(is, thinJar, entry, path);
			}
		}
	}    
	
	private void storeHashedLibEntries(JarEntry entry, String path) throws IOException, NoSuchAlgorithmException {
		String hash = hash(jf, entry);
		String libLine = "/" + path + '=' + hash;
		libEntries.add(libLine);
		prefix = hash.substring(0, 2) + "/";
		postfix = hash.substring(2, hash.length());
	}
	
	private static String hash(JarFile jf, ZipEntry entry) throws IOException, NoSuchAlgorithmException {
		InputStream eis = jf.getInputStream(entry);
		MessageDigest digest = MessageDigest.getInstance("sha-256");
		byte[] buffer = new byte[4096];
		int read = -1;

		while ((read = eis.read(buffer)) != -1) {
			digest.update(buffer, 0, read);
		}
		byte[] digested = digest.digest();
		return convertToHexString(digested);
	}

	private static String convertToHexString(byte[] digested) {
		StringBuilder stringBuffer = new StringBuilder();
		for (int i = 0; i < digested.length; i++) {
			stringBuffer.append(Integer.toString((digested[i] & 0xff) + 0x100, 16).substring(1));
		}
		return stringBuffer.toString();
	}

	
	private void storeLibraryInZip(ZipOutputStream libJar, JarEntry entry)
			throws IOException, NoSuchAlgorithmException {
		String path = entry.getName();
		try (InputStream is = jf.getInputStream(entry)) {
			putNextLibEntry(libJar);
			path = prefix + postfix + ".jar";
			writeEntry(is, libJar, entry, path);
		}
	}

	private void storeLibraryInDir(JarEntry entry) throws IOException, NoSuchAlgorithmException {
		if (!libIndexCache.exists()) {
			libIndexCache.mkdirs();
		}
		File jarDir = new File(libIndexCache, prefix.toString());
		if (!jarDir.exists()) {
			jarDir.mkdirs();
		}
		File libFile = new File(jarDir, postfix.toString() + ".jar");
		InputStream eis = jf.getInputStream(entry);
		JarInputStream zis = new JarInputStream(eis);

		try (ZipOutputStream libJar = new ZipOutputStream(new FileOutputStream(libFile))) {
			ZipEntry jarEntry = null;
			while ((jarEntry = zis.getNextJarEntry()) != null) {
				writeEntry(zis, libJar, jarEntry, jarEntry.getName());
			}
		} finally {
			zis.close();
			eis.close();
		}
	}

	private void putNextLibEntry(ZipOutputStream libJar) throws IOException {
		if (!prefixList.contains(prefix)) {
			libJar.putNextEntry(new ZipEntry(prefix));
			libJar.closeEntry();
			prefixList.add(prefix);
		}
	}

	private void writeEntry(InputStream is, ZipOutputStream zos, ZipEntry entry, String entryName) throws IOException {
		try {
			zos.putNextEntry(new ZipEntry(entryName));
			byte[] buffer = new byte[4096];
			int read = -1;
			while ((read = is.read(buffer)) != -1) {
				zos.write(buffer, 0, read);
			}
		} finally {
			zos.closeEntry();
		}
	}

	private void addLibIndexFileToThinJar(JarOutputStream thinJar) throws IOException {
		thinJar.putNextEntry(new ZipEntry(SPRING_LIB_INDEX_FILE));
		try {
			for (String libEntry : libEntries) {
				thinJar.write(libEntry.getBytes(StandardCharsets.UTF_8));
				thinJar.write('\n');
			}
		} finally {
			thinJar.closeEntry();
		}
	}

	static class SpringBootManifest {
		final String springStartClass;
		final String springBootClasses;
		final String springBootLib;

		SpringBootManifest(Manifest manifest) throws IOException {
			springStartClass = manifest.getMainAttributes().getValue(SPRING_START_CLASS_HEADER);
			springBootClasses = manifest.getMainAttributes().getValue(SPRING_BOOT_CLASSES_HEADER);
			springBootLib = manifest.getMainAttributes().getValue(SPRING_BOOT_LIB_HEADER);

		}
	}
}
