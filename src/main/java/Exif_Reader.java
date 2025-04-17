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
import ij.*;
import ij.io.*;
import java.io.*;
import java.util.*;
import ij.plugin.*;
import ij.text.*;
import com.drew.metadata.*;
import com.drew.imaging.*;

/**
 * Extracts and displays the Exif data stored in JPEG images.
 * See <a href="https://imagej.net/ij/plugins/exif-reader.html">here</a>
 * for documentation.
 *
 * @author Gali Baler
 */
public class Exif_Reader implements PlugIn {

	@Override
	public void run(String arg) {
		String directory = null;
		String name = null;
		FileInfo fi = null;
		ImagePlus imp = WindowManager.getCurrentImage();
		if (imp!=null)
			fi = imp.getOriginalFileInfo();
		if (imp!=null && fi!=null) {
			directory = fi.directory;
			name = fi.fileName;
			if ((name==null||name.equals("")) && imp.getStack().isVirtual())
				name = imp.getStack().getSliceLabel(imp.getCurrentSlice());
		} else {
			OpenDialog od = new OpenDialog("Open JPEG...", arg);
			directory = od.getDirectory();
			name = od.getFileName();
		}
		if (name==null)
			return;
		String path = directory + name;
		String metadata = getMetadata(path);
		if (!metadata.startsWith("Error:")) {
			String headings = "Tag\tValue";
			new TextWindow("EXIF Metadata for "+name, headings, metadata, 450, 500);
		} else
			IJ.showMessage("Exif Reader", metadata);
	}

	/** Returns the Exif metadata of the specified file, or a 
		string that starts with "Error:" if there is an error. */
	public static String getMetadata(String path) {
		File file = new File(path);
		StringBuilder sb = new StringBuilder();
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(file);
			for (Directory dir : metadata.getDirectories()) {
				for (Tag tag : dir.getTags()) {
					sb.append(tag);
					sb.append("\n");
				}
			}
		} catch (Exception e) {
			String msg = e.getMessage();
			if (msg==null) msg = ""+e;
			return "Error: " + msg + "\n"+path; 
		}
		String metadata = sb.toString();
		if (metadata!=null)
			metadata = metadata.replace(" - ", ":\t");
		return metadata;
	}
   
}
