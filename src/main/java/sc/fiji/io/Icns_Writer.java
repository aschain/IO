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
// Save the active image as .icns file
package sc.fiji.io;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;

import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;

import sc.fiji.io.icns.IcnsCodec;
import sc.fiji.io.icns.IconSuite;

public class Icns_Writer implements PlugIn {

	public void run (String arg) {
		ImagePlus image = WindowManager.getCurrentImage();
		if (image == null) {
			IJ.showStatus("No image is open");
			return;
		}

		// TODO: support saving more than one image

		int w = image.getWidth(), h = image.getHeight();
		IconSuite icons = new IconSuite();
		if (w == 16 && h == 16)
			icons.setSmallIcon((BufferedImage)image.getImage());
		else if (w == 32 && h == 32)
			icons.setLargeIcon((BufferedImage)image.getImage());
		else if (w == 48 && h == 48)
			icons.setHugeIcon((BufferedImage)image.getImage());
		else if (w == 128 && h == 128)
			icons.setThumbnailIcon((BufferedImage)image.getImage());
		else {
			IJ.error("Invalid dimensions: " + w + "x" + h +
					"\nMust be one of 16x16, 32x32, " +
					"48x48 or 128x128");
			return;
		}

		String path = arg;
		if (path == null || path.length() < 1) {
			String name = image.getTitle();
			SaveDialog sd = new SaveDialog("Save as Icns",
					name, ".icns");
			String directory = sd.getDirectory();
			if (directory == null)
				return;

			if (!directory.endsWith("/"))
				directory += "/";
			name = sd.getFileName();
			path = directory + name;
		}

		try {
			FileOutputStream out = new FileOutputStream(path);
			IcnsCodec codec = new IcnsCodec();
			codec.encode(icons, out);
			out.close();
		} catch (IOException e) {
			IJ.error("Failed to write " + path + ": " + e);
		}
	}
}
