/*-
 * #%L
 * IO plugin for Fiji.
 * %%
 * Copyright (C) 2008 - 2025 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.io;

// FlexibleFileOpener
// ------------------
// Class to allow plugins ImageJ to semi-transparently access
// compressed (GZIP, ZLIB) raw image data.
// Used by Nrrd_Reader
// 
// - It can add a GZIPInputStream or ZInputStream onto the
// stream provided to File opener
// - Can also specify a pre-offset to jump to before FileOpener sees the 
// stream.  This allows one to read compressed blocks from a file that
// has not been completely compressed. 
//
// NB GZIP is not the same as ZLIB
// GZIP has a longer header; the compression algorithm is identical

// (c) Gregory Jefferis 2007
// Department of Zoology, University of Cambridge
// jefferis@gmail.com
// All rights reserved
// Source code released under Lesser Gnu Public License v2

import com.jcraft.jzlib.ZInputStream;

import ij.IJ;
import ij.io.FileInfo;
import ij.io.FileOpener;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.zip.GZIPInputStream;

class FlexibleFileOpener extends FileOpener {
	
	public static final int UNCOMPRESSED = 0;
	public static final int GZIP = 1;
	public static final int ZLIB = 2;
	
	int gunzipMode=UNCOMPRESSED;
	// the offset that will be skipped before FileOpener sees the stream
	long preOffset=0; 
	
	public FlexibleFileOpener(FileInfo fi) {
		this(fi,fi.fileName.toLowerCase().endsWith(".gz")?GZIP:UNCOMPRESSED,0);
	}
	public FlexibleFileOpener(FileInfo fi, int gunzipMode) {
		this(fi,gunzipMode,0);
	}
	
	public FlexibleFileOpener(FileInfo fi, int gunzipMode, long preOffset) {
		super(fi);
		this.gunzipMode=gunzipMode;
		this.preOffset=preOffset;
	}
	
	public InputStream createInputStream(FileInfo fi) throws IOException, MalformedURLException {
		// use the method in the FileOpener class to generate an input stream
		InputStream is=super.createInputStream(fi);
	
		// Skip if required
		if (preOffset!=0) is.skip(preOffset);

		// Just return orgiinal input stream if uncompressed
		if (gunzipMode==UNCOMPRESSED) return is;

		//  else put a regular GZIPInputStream on top 
		// NB should only do this if less than 138s because that will take 
		// care of things automatically if the file ends in gz
		
		if(gunzipMode==GZIP){
			boolean lessThan138s = IJ.getVersion().compareTo("1.38s")<0;
			if(lessThan138s || !fi.fileName.toLowerCase().endsWith(".gz")) return new GZIPInputStream(is,50000);
			else return is;
		}
		
		// or put a ZInputStream on top (from jzlib)
		if(gunzipMode==ZLIB) return new ZInputStream(is);
		
		// fallback
		throw new IOException("Incorrect GZIP mode: "+gunzipMode);
	}
}
