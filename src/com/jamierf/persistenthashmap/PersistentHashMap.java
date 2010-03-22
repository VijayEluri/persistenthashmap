/**
 * 
 * This file is part of the PersistentHashMap library.
 * Copyright (C) 2010 Jamie Furness (http://www.jamierf.co.uk)
 * License: http://www.gnu.org/licenses/gpl.html GPL version 3 (or higher)
 * 
 */

package com.jamierf.persistenthashmap;

import com.jamierf.persistenthashmap.serializers.OOSSerializer;
import com.jamierf.persistenthashmap.serializers.ObjectSerializer;
import com.jamierf.persistenthashmap.util.FileUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PersistentHashMap<K extends Serializable, V extends Serializable> implements Map<K, V> {
	
	protected static final int MAX_CAPACITY = 1000000001;
	protected static final DecimalFormat format = new DecimalFormat("000000000");
	protected static final int ZIP_BUFFER_SIZE = 4096;
	
	protected EntrySet<K, V> entrySet;
	protected KeySet<K, V> keySet;
	protected ValueSet<K, V> valueSet;
	
	protected File root;
	protected File keyStore;
	protected File valueStore;
	
	protected ObjectSerializer serializer;
	
	public PersistentHashMap(File root) {
		this(root, new OOSSerializer());
	}
	
	public PersistentHashMap(File root, ObjectSerializer serializer) {
		this.root = root;
		this.serializer = serializer;
		
		if (!root.isDirectory())
			root.mkdir();
		
		keyStore = new File(root, "keys");
		if (!keyStore.isDirectory())
			keyStore.mkdir();
		
		valueStore = new File(root, "values");
		if (!valueStore.isDirectory())
			valueStore.mkdir();
		
		entrySet = new EntrySet<K, V>(this);
		keySet = new KeySet<K, V>(this);
		valueSet = new ValueSet<K, V>(this);
	}
	
	public synchronized boolean containsKey(Object key) {
		try {
			return getFileName(key, false) != null;
		}
		catch (IOException ioe) {
			return false;
		}
	}
	
	public synchronized boolean containsValue(Object v) {
		Collection<V> values = values();
		for (V value : values)
			if (v.equals(value))
				return true;
		
		return false;
	}
	
	@SuppressWarnings("unchecked")
	public synchronized V get(Object key) {
		try {
			String fileName = getFileName(key, false);
			if (fileName == null)
				return null;
	
			File valueFile = new File(valueStore, fileName);
			return (V) readObject(valueFile);
		}
		catch (ClassNotFoundException cnfe) {
			return null;
		}
		catch (IOException ioe) {
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public synchronized V put(K key, V value) {
		try {
			V oldValue = null;
			
			String fileName = getFileName(key, true);
			
			File keyFile = new File(keyStore, fileName);
			File valueFile = new File(valueStore, fileName);
			
			if (valueFile.exists())
				oldValue = (V) readObject(valueFile);
			
			writeObject(keyFile, key);
			writeObject(valueFile, value);
			
			return oldValue;
		}
		catch (ClassNotFoundException cnfe) {
			return null;
		}
		catch (IOException ioe) {
			return null;
		}
	}
	
	public synchronized void putAll(Map<? extends K, ? extends V> m) {
		for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
			put (e.getKey(), e.getValue());
		}
	}
	
	@SuppressWarnings("unchecked")
	public synchronized V remove(Object key) {
		try {
			String fileName = getFileName(key, false);
			
			File keyFile = new File(keyStore, fileName);
			File valueFile = new File(valueStore, fileName);
			
			V oldValue = (V) readObject(valueFile);
			
			keyFile.delete();
			valueFile.delete();
			
			// TODO: We also need to delete their directories if empty otherwise isEmpty() will fail...
			// TODO: Shift any subsequent files down
			
			return oldValue;
		}
		catch (ClassNotFoundException cnfe) {
			return null;
		}
		catch (IOException ioe) {
			return null;
		}
	}
	
	public synchronized boolean isEmpty() {
		return keyStore.listFiles().length == 0;
	}
	
	public synchronized int size() {
		if (isEmpty())
			return 0;
		
		return countDirectory(keyStore);
	}
	
	public synchronized void clear() {
		File[] keyFiles = root.listFiles();
		for (File f : keyFiles) 
			FileUtils.deleteDirectory(f);
		
		keyStore.mkdir();
		valueStore.mkdir();
	}
	
	public synchronized Collection<V> values() {
		return valueSet;
	}
	
	public synchronized Set<K> keySet() {
		return keySet;
	}
	
	public synchronized Set<Map.Entry<K, V>> entrySet() {
		return entrySet;
	}
	
	public synchronized boolean toZip(File zipFile) {
		try {
			ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile)); 
			zipDirectory(root, zos);
			zos.close();
			
			return true;
		}
		catch (IOException ioe) {
			return false;
		}
	}
	
	protected synchronized void writeObject(File f, Serializable o) throws IOException {
		serializer.writeObject(f, o);
	}
	
	protected synchronized Object readObject(File f) throws IOException, ClassNotFoundException {
		return serializer.readObject(f);
	}
	
	protected void zipDirectory(File dir, ZipOutputStream zos) throws IOException {
		File[] files = dir.listFiles();
		for (File f : files) {
			if (f.isDirectory()) {
				zipDirectory(f, zos);
				continue;
			}
			
			zos.putNextEntry(new ZipEntry(f.getPath()));
			
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(f), ZIP_BUFFER_SIZE);
			
			byte[] buffer = new byte[ZIP_BUFFER_SIZE];
			for (int count;(count = in.read(buffer, 0, ZIP_BUFFER_SIZE)) != -1;)
				zos.write(buffer, 0, count);
			
			in.close();
		}
	}
	
	protected int countDirectory(File dir) {
		if (!dir.isDirectory())
			return 0;
		
		int count = 0;
		
		File[] files = dir.listFiles();
		for (File f : files) {
			if (f.isDirectory())
				count += countDirectory(f);
			else
				count++;
		}
		
		return count;
	}
	
	@SuppressWarnings("unchecked")
	protected String getFileName(Object key, boolean create) throws IOException {
		try {
			String fileName = getFileName(key);
			File keyFile = null;
			
			// Handle the directories
			for (int i = 2;i < 9;i += 3) {
				String dirName = fileName.substring(0, i);
				File dirFile = new File(keyStore, dirName);
				if (!dirFile.isDirectory()) {
					if (!create)
						return null;
					
					dirFile.mkdir(); // Make the key dir
					new File(valueStore, dirName).mkdir(); // Make the value dir
				}
			}
			
			// Find the first position which isn't being used
			String fileNameI = null;
			for (int i = 0;true;i++) {
				fileNameI = fileName + i;
				
				keyFile = new File(keyStore, fileNameI);
				if (!keyFile.exists()) { // If there isn't such a key, stick it here
					if (!create)
						return null;
					else
						break;
				}
				
				K candidateKey = (K) readObject(keyFile);
				if (key.equals(candidateKey)) // If the key matches, overwrite it
					break;
				
				// otherwise, try the next position
			}
			
			return fileNameI;
		}
		catch (ClassNotFoundException cnfe) {
			return null;
		}
	}
	
	protected String getFileName(Object key) {
		String fileName = format.format((key.hashCode() & 0x7FFFFFFF) % MAX_CAPACITY);
		StringBuffer buffer = new StringBuffer(12);
		
		buffer.append(fileName.substring(7)).append(File.separator);	// directory level 1 name
		buffer.append(fileName.substring(5,7)).append(File.separator);	// directory level 2 name
		buffer.append(fileName.substring(3,5)).append(File.separator);	// directory level 3 name
		
		// file name
		buffer.append(fileName.substring(0,3));
		
		return buffer.toString();
	}
	
	protected class FileIterator implements Iterator<File> {
		private static final int MAX_DEPTH = 4;
		private static final int NOT_STARTED = -1;
		
		protected File root;
		protected File current;
		protected File[][] files;
		protected int[] indices;
		
		public FileIterator(File root) {
			this.root = root;
			
			files = new File[MAX_DEPTH][];
			indices = new int[MAX_DEPTH];
			
			Arrays.fill(indices, NOT_STARTED);
			
			current = null;
		}

		public boolean hasNext() {
			synchronized (PersistentHashMap.this) {
				try {
					return iterateFiles(root, 0, false) != null;
				} catch (IOException e) {
					return false;
				}
			}
		}

		public File next() {
			synchronized (PersistentHashMap.this) {
				try {
					current = iterateFiles(root, 0, true);
					return current;
				}
				catch (IOException ioe) {
					return null;
				}
			}
		}
		
		public void remove() {
			synchronized (PersistentHashMap.this) {
				if (current == null)
					return;
				
				current.delete();
			}
		}
		
		protected File iterateFiles(File dir, int index, boolean increment) throws IOException {
			// If we haven't stared at this depth, initialize the file list and start at the beginning
			if (indices[index] == NOT_STARTED) {
				files[index] = dir.listFiles();
				indices[index] = 0;
			}
			
			// If we have finished this depth
			if (indices[index] >= files[index].length){
				indices[index] = NOT_STARTED;
				return null;
			}
			
			File currentFile = files[index][indices[index]];
			// It is a directory so we look inside it
			if (currentFile.isDirectory()) {
				File subFile = null;
				
				while ((subFile = iterateFiles(currentFile, index + 1, increment)) == null) {
					indices[index]++;
					if (indices[index] == files[index].length) {
						indices[index] = NOT_STARTED;
						return null;
					}
					currentFile = files[index][indices[index]];
				}
				
				return subFile;
			}
			// This is a file, it will do just fine!
			else {
				if (increment)
					indices[index]++;
				
				return currentFile;
			}
		}
	}
}
