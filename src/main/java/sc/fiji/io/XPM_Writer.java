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
// Save the active image as .xpm file
package sc.fiji.io;

import ij.IJ;
import ij.ImagePlus;
import ij.LookUpTable;
import ij.WindowManager;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

public class XPM_Writer implements PlugIn {

	public void run (String arg) {
		ImagePlus image = WindowManager.getCurrentImage();
		if (image == null) {
			IJ.error("No image is open");
			return;
		}
		byte[] pixels = null;
		try {
			pixels = (byte[])image.getProcessor().getPixels();
		} catch (ClassCastException e) {
			IJ.error("Can only handle 8-bit images");
			return;
		}
		int w = image.getWidth();
		int h = image.getHeight();

		// TODO: add a dialog to handle transparency
		char[] letters = new char[256];
		int currentAsciiValue = 0x20;
		for (int i = 0; i < w * h; i++) {
			int value = pixels[i] & 0xff;
			if (letters[value] == 0) {
				// TODO: support 2-letter colors
				if (currentAsciiValue > 0x7d) {
					IJ.error("This image needs more than "
						+ (0x7f - 0x20)
						+ "colors.\n"
						+ "Please convert to RGB and "
						+ "then to 8-bit Color, "
						+ "reducing the number of "
						+ "colors.");
					return;
				}
				letters[value] = (char)currentAsciiValue++;
			}
		}
		// normalize the letters
		currentAsciiValue = 0x20;
		for (int i = 0; i < letters.length; i++)
			if (letters[i] > 0) {
				letters[i] = (char)currentAsciiValue++;
				if (letters[i] >= '"')
					letters[i]++;
			}

		String name = image.getTitle();
		String path = arg;
		if (path == null || path.length() < 1) {
			SaveDialog sd = new SaveDialog("Save as XPM",
					name, ".xpm");
			String directory = sd.getDirectory();
			if (directory == null)
				return;

			if (!directory.endsWith("/"))
				directory += "/";
			name = sd.getFileName();
			path = directory + name;
		}

		try {
			PrintStream out =
				new PrintStream(new FileOutputStream(path));
			writeHeader(out, name, w, h, currentAsciiValue - 0x20);
			writeColorTable(out, letters, image.createLut());
			writeImage(out, w, h, pixels, letters);
			out.close();
		} catch (IOException e) {
			IJ.error("Could not write to '" + path + "'");
		}
	}

	protected void writeHeader(PrintStream out,
			String name, int w, int h, int colorCount)
			throws IOException {
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if ((c < 'A' || c > 'Z') && (c < 'a' || c > 'z') &&
					c != '_' &&
					(i == 0 || c < '0' || c > '9'))
				name = name.substring(0, i) + '_'
					+ name.substring(i + 1);
		}
		out.println("/* XPM */");
		out.println("static char* " + name + "[] = {");
		out.println("\"" + w + " " + h + " " + colorCount + " 1\",");
	}

	protected static String toHex(byte b) {
		final String hex = "0123456789abcdef";
		return "" + hex.charAt((b & 0xf0) >> 4) + hex.charAt(b & 0xf);
	}

	protected void writeColorTable(PrintStream out,
			char[] letters, LookUpTable lut) throws IOException {
		byte[] reds = lut.getReds();
		byte[] greens = lut.getGreens();
		byte[] blues = lut.getBlues();
		for (int i = 0; i < letters.length; i++)
			if (letters[i] > 0)
				out.println("\"" + letters[i] + " c #"
						+ toHex(reds[i])
						+ toHex(greens[i])
						+ toHex(blues[i]) + "\",");
	}

	protected void writeImage(PrintStream out,
			int w, int h, byte[] pixels, char[] letters)
			throws IOException {
		for (int j = 0; j < h; j++) {
			String line = "\"";
			for (int i = 0; i < w; i++)
				line += letters[pixels[i + j * w]];
			line += "\"" + (j < h - 1 ? "," : "};");
			out.println(line);
		}
	}
}
