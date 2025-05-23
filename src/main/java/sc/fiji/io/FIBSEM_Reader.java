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

import ij.CompositeImage;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Opens the proprietary FIB-SEM format used at Janelia
 *
 * The reference MATLAB implementation from Janelia is available at:
 * https://github.com/david-hoffman/FIB-SEM-Aligner/blob/master/matlab
 *
 * This implementation is based on the 2017-07-25 version of the reference implementation.
 * File versions up to 8 are supported. Eight bit images are not supported.
 *
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public class FIBSEM_Reader implements PlugIn
{
	/**
	 * Opens it either as:
	 * UnsignedShort with the range [0..65535] where 0 == -10 Volts and 65535 == +10 Volts (default)
	 * Float with a range from -10 Volts to +10 Volts
	 *
	 * Note, the voltage might be different in future versions, it is stored in FIBSEMData.detMin and FIBSEMData.detMax
	 */
	public static boolean openAsFloat = false;

	/**
	 * Match the "raw" output format of the reference implementation, which does not scale the data.
	 *
	 * Only used if `openAsFloat` is false.
	 */
	public static boolean openAsRaw = false;

	/**
	 * Stores the current header if somebody wants access to it
	 */
	FIBSEMData header;

	/**
	 * @return - the header of the last opened FIB-SEM file
	 */
	public FIBSEMData getHeader() { return header; }

	@Override
	public void run( final String filename )
	{
		File f = new File( filename );

		// try to open, otherwise query
		if ( !f.exists() )
		{
			final OpenDialog od = new OpenDialog( "Open FIB-SEM raw file", null );

			f = new File( od.getDirectory() + od.getFileName() );

			if ( !f.exists() )
			{
				IJ.log( "Cannot find file '" + f.getAbsolutePath() + "'" );
				return;
			}
		}

		try
		{
			FileInputStream file = new FileInputStream( f );
			
			final FIBSEMData header = parseHeader( file );

			if ( header == null )
			{
				IJ.log( "The file '" + f.getAbsolutePath() + "' is not a FIB-SEM raw file, the magic number does not match." );
				return;
			}

			//System.out.println( header );
			file.close();

			// re-open the file to be able to jump exactly to position 1024
			file = new FileInputStream( f );
			
			final ImagePlus imp = readFIBSEM( header, file, openAsFloat );
			file.close();

			if ( imp == null )
				return;

			this.header = header;

			// set the filename
			imp.setTitle( f.getName() );
			Calibration cal = imp.getCalibration();
			cal.setXUnit( "nm" );
			cal.setYUnit( "nm" );
			cal.pixelWidth = header.pixelSize;
			cal.pixelHeight = header.pixelSize;

			imp.show();
		}
		catch ( FileNotFoundException e )
		{
			IJ.log( "Error opening the file '" + f.getAbsolutePath() + "': " + e );
			return;
		}
		catch ( IOException e )
		{
			IJ.log( "Error parsing the file '" + f.getAbsolutePath() + "': " + e );
			return;
		}
	}

	/**
	 * Determines if the given file is a FIB-SEM file based on the magic number (first 4 bytes)
	 *
	 * @param f - the File
	 * @return
	 */
	public static boolean isFIBSEM( final File f )
	{
		try
		{
			final FileInputStream file = new FileInputStream( f );
			final DataInputStream s = new DataInputStream( file );
			final long magicNumber = getUnsignedInt( s.readInt() );

			s.close();

			if ( magicNumber == 3555587570l )
				return true;
			else
				return false;
		}
		catch ( Exception e )
		{
			return false;
		}
	}

	public ImagePlus readFIBSEM( final FIBSEMData header, final FileInputStream file, boolean openAsFloat ) throws IOException
	{
		// go to position 1024
		file.skip( 1024 );

		ImagePlus imp;
		double[] minmax = new double[] { Double.MAX_VALUE, Double.MIN_VALUE };

		final ImageProcessor[] channels = readChannels( header, file, minmax, openAsFloat );
		if ( header.numChannels == 1 )
		{
			imp = new ImagePlus( "", channels[ 0 ] );
		}
		else
		{

			final ImageStack stack = new ImageStack( (int)header.xRes, (int)header.yRes );

			for ( int c = 0; c < header.numChannels; ++c )
				stack.addSlice( "channel " + c, channels[ c ] );
			imp = new ImagePlus( "", stack);
			imp.setDimensions( 1, header.numChannels, 1 );
			imp =  new CompositeImage( imp, CompositeImage.GRAYSCALE );
		}

		imp.setDisplayRange( minmax[ 0 ], minmax[ 1 ] );
		return imp;
	}

	final ImageProcessor[] readChannels( final FIBSEMData header, final FileInputStream file, double[] minmax, boolean openAsFloat ) throws IOException
	{
		final int numChannels = header.numChannels;

		// it is always unsigned short
		final byte[] slice = new byte[ (int)header.xRes * (int)header.yRes * numChannels * 2 ];
		file.read( slice );
		final ByteBuffer buffer = ByteBuffer.wrap( slice );
		final ShortBuffer shortBuffer = buffer.asShortBuffer();

		// for the display range
		double min = Double.MAX_VALUE;
		double max = Double.MIN_VALUE;

		final ImageProcessor[] channels = new ImageProcessor[ numChannels ];

		if ( openAsFloat )
		{
			final float[][] floatSlice = new float[ numChannels ][ (int)header.xRes * (int)header.yRes ];


			for ( int i = 0; i < floatSlice[ 0 ].length; ++i )
			{
				for ( int c = 0; c < numChannels; ++c )
				{
					int j = i * numChannels + c;
					final short v = shortBuffer.get( j );

					final float v2 = scale( header, v, c );

					if ( v2 < min ) min = v2;
					if ( v2 > max ) max = v2;
					floatSlice[ c ][ i ] = v2;
				}
			}

			for ( int c = 0; c < numChannels; ++c )
				channels[ c ] = new FloatProcessor( (int)header.xRes, (int)header.yRes, floatSlice[ c ], null );
		}
		else
		{			
			final short[][] shortSlice = new short[ numChannels ][ (int)header.xRes * (int)header.yRes ];

			final float minVolts = -10;//(float)header.detMin;
			final float rangeVolts = 20;//(float)header.detMax - (float)header.detMin;

			int[] cropped = new int[ numChannels ];

			for ( int i = 0; i < shortSlice[ 0 ].length; ++i )
			{
				
				for ( int c = 0; c < numChannels; ++c )
				{
					int j = i * numChannels + c;
					short v = shortBuffer.get( j );

					if ( openAsRaw ) {
						v += 32768;
					} else {
						float fv = scale(header, v, c);
						// Matching fibsem2tiff.m, only versions 1-6 receive this scaling, so
						// for versions 7-8 the scaled version is the same as `openAsFloat`.
						if (header.fileVersion <= 6)
							fv = (fv - minVolts) / rangeVolts * 65535.0f;
						int iv = Math.round(fv);

						if (iv < 0) {
							iv = 0;
							++cropped[c];
						}

						if (iv > 65535) {
							iv = 65535;
							++cropped[c];
						}

						v = (short) iv;
					}

					if (v < min) min = v;
					if (v > max) max = v;
					shortSlice[ c ][ i ] = v;
				}
				
			}

			for ( int i = 0; i < numChannels; ++i )
				if ( cropped[ i ] > 0 )
					IJ.log( "Warning (channel " + (i+1) + "/" + numChannels + "): " + cropped[ i ] + " values have been truncated as they were out of range of 16 bit. To verify this, please open the image as float (see http://fiji.sc/wiki/index.php/FIBSEM_importer#Open_image_as_float)" );


			for ( int c = 0; c < numChannels; ++c )
				channels[ c ] = new ShortProcessor( (int)header.xRes, (int)header.yRes, shortSlice[ c ], null );

		}

		minmax[ 0 ] = min;
		minmax[ 1 ] = max;

		return channels;
	}

	final float scale(FIBSEMData header, short value, int channel) {
		if ( header.fileVersion <= 6 ) {
			return header.offset[ channel ] + value * header.gain[ channel ];
		} else {
			return (value - header.gain[ channel ]) * header.secondOrder[ channel ];
		}
	}

	/**
	 * Parses the header and sets the {@link FileInputStream} to right location where the raw image data starts
	 *
	 * @param file - the input file
	 * @return the {@link FIBSEMData} that contains all meta-data or null if the magic number (file id) does not match
	 * @throws IOException
	 */
	public FIBSEMData parseHeader( final FileInputStream file ) throws IOException
	{
		// read the header
		final DataInputStream s = new DataInputStream( file );
		final FIBSEMData data = new FIBSEMData();
		byte[] tmp;

		//
		// parse the data
		//

		// fseek(fid,0,'bof'); FIBSEMData.FileMagicNum = fread(fid,1,'uint32'); % Read in magic number, should be 3555587570
		data.magicNumber = getUnsignedInt( s.readInt() );

		if ( data.magicNumber != 3555587570l )
			return null;

		// fseek(fid,4,'bof'); FIBSEMData.FileVersion = fread(fid,1,'uint16'); % Read in file version number
		data.fileVersion = getUnsignedShort( s.readShort() );
		// fseek(fid,6,'bof'); FIBSEMData.FileType = fread(fid,1,'uint16'); % Read in file type, 1 is Zeiss Neon detectors
		data.fileType = getUnsignedShort( s.readShort() );
		// fseek(fid,8,'bof'); FIBSEMData.SWdate = fread(fid,10,'*char')'; % Read in SW date
		tmp = new byte[ 10 ];
		s.read( tmp );
		data.swDate = new String( tmp );
		s.skip( 6 );
		// fseek(fid,24,'bof'); FIBSEMData.TimeStep = fread(fid,1,'double'); % Read in AI sampling time (including oversampling) in seconds
		data.timeStep = s.readDouble();
		// fseek(fid,32,'bof'); FIBSEMData.ChanNum = fread(fid,1,'uint8'); % Read in number of channels
		data.numChannels = getUnsignedByte( s.readByte() );
		data.offset = new float[ data.numChannels ];
		data.gain = new float[ data.numChannels ];
		data.secondOrder = new float[ data.numChannels ];
		data.thirdOrder = new float[ data.numChannels ];
		// fseek(fid,33,'bof'); FIBSEMData.EightBit = fread(fid,1,'uint8'); % Read in 8-bit data switch
		data.eightBit = getUnsignedByte( s.readByte() );
		s.skip( 2 );


		if ( data.fileVersion == 1 )
		{
			// fseek(fid,36,'bof'); FIBSEMData.Scaling = single(fread(fid,[4,FIBSEMData.ChanNum],'double')); % Read in AI channel scaling factors, (col#: AI#), (row#: offset, gain, 2nd order, 3rd order)
			for ( int c = 0; c < data.numChannels; ++c )
			{
				data.offset[ c ] = (float)s.readDouble();
				data.gain[ c ] = (float)s.readDouble();
				data.secondOrder[ c ] = (float)s.readDouble();
				data.thirdOrder[ c ] = (float)s.readDouble();
			}

			s.skip( 64 - data.numChannels*8*4 );
		}
		else if ( data.fileVersion >= 2 && data.fileVersion <= 6)
		{
			// fseek(fid,36,'bof'); FIBSEMData.Scaling = fread(fid,[4,FIBSEMData.ChanNum],'single');
			for ( int c = 0; c < data.numChannels; ++c )
			{
				data.offset[ c ] = s.readFloat();
				data.gain[ c ] = s.readFloat();
				data.secondOrder[ c ] = s.readFloat();
				data.thirdOrder[ c ] = s.readFloat();
			}

			s.skip( 64 - data.numChannels*4*4 );
		}
		else
		{
			// fseek(fid,36,'bof'); FIBSEMData.Scaling = fread(fid,[4,2],'single');
			// Note that this should be equivalent to the above and is only made explicit for consistency with the
			// reference implementation.
			for ( int c = 0; c < 2; ++c )
			{
				data.offset[ c ] = s.readFloat();
				data.gain[ c ] = s.readFloat();
				data.secondOrder[ c ] = s.readFloat();
				data.thirdOrder[ c ] = s.readFloat();
			}

			s.skip( 64 - 2*4*4 );
		}

		// fseek(fid,100,'bof'); FIBSEMData.XResolution = fread(fid,1,'uint32'); % X resolution
		data.xRes = getUnsignedInt( s.readInt() );
		// fseek(fid,104,'bof'); FIBSEMData.YResolution = fread(fid,1,'uint32'); % Y resolution
		data.yRes = getUnsignedInt( s.readInt() );

		if ( data.fileVersion == 1 || data.fileVersion == 2 || data.fileVersion == 3 )
		{
		    // fseek(fid,108,'bof'); FIBSEMData.Oversampling = fread(fid,1,'uint8'); % AI oversampling
			data.oversampling = getUnsignedByte( s.readByte() );
		    // fseek(fid,109,'bof'); FIBSEMData.AIDelay = fread(fid,1,'int16'); % Read AI delay (# of samples)
			data.AIdelay = s.readShort();
		}
		else
		{
		    // fseek(fid,108,'bof'); FIBSEMData.Oversampling = fread(fid,1,'uint16'); % AI oversampling
			data.oversampling = getUnsignedShort( s.readShort() );
			s.skip( 1 );
		}

		// fseek(fid,111,'bof'); FIBSEMData.ZeissScanSpeed = fread(fid,1,'uint8'); % Scan speed (Zeiss #)
		data.zeissScanSpeed = getUnsignedByte( s.readByte() );

		if ( data.fileVersion == 1 || data.fileVersion == 2 || data.fileVersion == 3 )
		{
		    // fseek(fid,112,'bof'); FIBSEMData.ScanRate = fread(fid,1,'double'); % Actual AO (scanning) rate
			data.scanRate = s.readDouble();
			// fseek(fid,120,'bof'); FIBSEMData.FramelineRampdownRatio = fread(fid,1,'double'); % Frameline rampdown ratio
			data.framelineRampdownRatio = s.readDouble();
			// fseek(fid,128,'bof'); FIBSEMData.Xmin = fread(fid,1,'double'); % X coil minimum voltage
			data.xMin = s.readDouble();
			// fseek(fid,136,'bof'); FIBSEMData.Xmax = fread(fid,1,'double'); % X coil maximum voltage
			data.xMax = s.readDouble();
			// FIBSEMData.Detmin = -10; % Detector minimum voltage
			data.detMin = -10.0;
			// FIBSEMData.Detmax = 10; % Detector maximum voltage
			data.detMax = 10.0;

			s.skip( 151 - 144 );
		}
		else
		{
		    // fseek(fid,112,'bof'); FIBSEMData.ScanRate = fread(fid,1,'single'); % Actual AO (scanning) rate
			data.scanRate = s.readFloat();
			// fseek(fid,116,'bof'); FIBSEMData.FramelineRampdownRatio = fread(fid,1,'single'); % Frameline rampdown ratio
			data.framelineRampdownRatio = s.readFloat();
			// fseek(fid,120,'bof'); FIBSEMData.Xmin = fread(fid,1,'single'); % X coil minimum voltage
			data.xMin = s.readFloat();
			// fseek(fid,124,'bof'); FIBSEMData.Xmax = fread(fid,1,'single'); % X coil maximum voltage
			data.xMax = s.readFloat();
			// fseek(fid,128,'bof'); FIBSEMData.Detmin = fread(fid,1,'single'); % Detector minimum voltage
			data.detMin = s.readFloat();
			// fseek(fid,132,'bof'); FIBSEMData.Detmax = fread(fid,1,'single'); % Detector maximum voltage
			data.detMax = s.readFloat();
			// fseek(fid,136,'bof'); FIBSEMData.DecimatingFactor = fread(fid,1,'uint16'); % Decimating factor
			data.decimatingFactor = getUnsignedShort( s.readShort() );

			s.skip( 151 - 138 );
		}

		// fseek(fid,151,'bof'); FIBSEMData.AI1 = fread(fid,1,'uint8'); % AI Ch1
		data.AI1 = getUnsignedByte( s.readByte() );
		// fseek(fid,152,'bof'); FIBSEMData.AI2 = fread(fid,1,'uint8'); % AI Ch2
		data.AI2 = getUnsignedByte( s.readByte() );
		// fseek(fid,153,'bof'); FIBSEMData.AI3 = fread(fid,1,'uint8'); % AI Ch3
		data.AI3 = getUnsignedByte( s.readByte() );
		// fseek(fid,154,'bof'); FIBSEMData.AI4 = fread(fid,1,'uint8'); % AI Ch4
		data.AI4 = getUnsignedByte( s.readByte() );

		s.skip( 180 - 155 );

		// fseek(fid,180,'bof'); FIBSEMData.Notes = fread(fid,200,'*char')'; % Read in notes		 */
		tmp = new byte[ 200 ];
		s.read( tmp );
		data.notes = new String( tmp );

		if ( data.fileVersion == 1 || data.fileVersion == 2 )
		{
			// fseek(fid,380,'bof'); FIBSEMData.DetA = fread(fid,10,'*char')'; % Name of detector A
			tmp = new byte[ 10 ];
			s.read( tmp );
			data.detectorA = new String( tmp );

			// fseek(fid,390,'bof'); FIBSEMData.DetB = fread(fid,18,'*char')'; % Name of detector B
			tmp = new byte[ 18 ];
			s.read( tmp );
			data.detectorB = new String( tmp );

			// fseek(fid,408,'bof'); FIBSEMData.Mag = fread(fid,1,'double'); % Magnification
			data.magnification = s.readDouble();

			// fseek(fid,416,'bof'); FIBSEMData.PixelSize = fread(fid,1,'double'); % Pixel size in nm
			data.pixelSize = s.readDouble();

			// fseek(fid,424,'bof'); FIBSEMData.WD = fread(fid,1,'double'); % Working distance in mm
			data.wd = s.readDouble();

			// fseek(fid,432,'bof'); FIBSEMData.EHT = fread(fid,1,'double'); % EHT in kV
			data.eht = s.readDouble();

			// fseek(fid,440,'bof'); FIBSEMData.SEMApr = fread(fid,1,'uint8'); % SEM aperture number
			data.semApr = getUnsignedByte( s.readByte() );

			// fseek(fid,441,'bof'); FIBSEMData.HighCurrent = fread(fid,1,'uint8'); % high current mode (1=on, 0=off)
			data.highCurrent = getUnsignedByte( s.readByte() );

			s.skip( 448 - 442 );

			// fseek(fid,448,'bof'); FIBSEMData.SEMCurr = fread(fid,1,'double'); % SEM probe current in A
			data.semCurr = s.readDouble();

			// fseek(fid,456,'bof'); FIBSEMData.SEMRot = fread(fid,1,'double'); % SEM scan roation in degree
			data.semRot = s.readDouble();

			// fseek(fid,464,'bof'); FIBSEMData.ChamVac = fread(fid,1,'double'); % Chamber vacuum
			data.chamVac = s.readDouble();

			// fseek(fid,472,'bof'); FIBSEMData.GunVac = fread(fid,1,'double'); % E-gun vacuum
			data.gunVac = s.readDouble();

			// fseek(fid,480,'bof'); FIBSEMData.SEMStiX = fread(fid,1,'double'); % SEM stigmation X
			data.semStiX = s.readDouble();

			// fseek(fid,488,'bof'); FIBSEMData.SEMStiY = fread(fid,1,'double'); % SEM stigmation Y
			data.semStiY = s.readDouble();

			// fseek(fid,496,'bof'); FIBSEMData.SEMAlnX = fread(fid,1,'double'); % SEM aperture alignment X
			data.semAlnX = s.readDouble();

			// fseek(fid,504,'bof'); FIBSEMData.SEMAlnY = fread(fid,1,'double'); % SEM aperture alignment Y
			data.semAlnY = s.readDouble();

			// fseek(fid,512,'bof'); FIBSEMData.StageX = fread(fid,1,'double'); % Stage position X in mm
			data.stageX = s.readDouble();

			// fseek(fid,520,'bof'); FIBSEMData.StageY = fread(fid,1,'double'); % Stage position Y in mm
			data.stageY = s.readDouble();

			// fseek(fid,528,'bof'); FIBSEMData.StageZ = fread(fid,1,'double'); % Stage position Z in mm
			data.stageZ = s.readDouble();

			// fseek(fid,536,'bof'); FIBSEMData.StageT = fread(fid,1,'double'); % Stage position T in degree
			data.stageT = s.readDouble();

			// fseek(fid,544,'bof'); FIBSEMData.StageR = fread(fid,1,'double'); % Stage position R in degree
			data.stageR = s.readDouble();

			// fseek(fid,552,'bof'); FIBSEMData.StageM = fread(fid,1,'double'); % Stage position M in mm
			data.stageM = s.readDouble();

			// fseek(fid,560,'bof'); FIBSEMData.BrightnessA = fread(fid,1,'double'); % Detector A brightness (%)
			data.brightnessA = s.readDouble();

			// fseek(fid,568,'bof'); FIBSEMData.ContrastA = fread(fid,1,'double'); % Detector A contrast (%)
			data.contrastA = s.readDouble();

			// fseek(fid,576,'bof'); FIBSEMData.BrightnessB = fread(fid,1,'double'); % Detector B brightness (%)
			data.brightnessB = s.readDouble();

			// fseek(fid,584,'bof'); FIBSEMData.ContrastB = fread(fid,1,'double'); % Detector B contrast (%)
			data.contrastB = s.readDouble();

			s.skip( 600 - 592 );

			// fseek(fid,600,'bof'); FIBSEMData.Mode = fread(fid,1,'uint8'); % FIB mode: 0=SEM, 1=FIB, 2=Milling, 3=SEM+FIB, 4=Mill+SEM, 5=SEM Drift Correction, 6=FIB Drift Correction, 7=No Beam, 8=External, 9=External+SEM
			data.mode = getUnsignedByte( s.readByte() );

			s.skip( 608 - 601 );

			// fseek(fid,608,'bof'); FIBSEMData.FIBFocus = fread(fid,1,'double'); % FIB focus in kV
			data.fibFocus = s.readDouble();

			// fseek(fid,616,'bof'); FIBSEMData.FIBProb = fread(fid,1,'uint8'); % FIB probe number
			data.fibProb = getUnsignedByte( s.readByte() );

			s.skip( 624 - 617 );

			// fseek(fid,624,'bof'); FIBSEMData.FIBCurr = fread(fid,1,'double'); % FIB emission current
			data.fibCurr = s.readDouble();

			// fseek(fid,632,'bof'); FIBSEMData.FIBRot = fread(fid,1,'double'); % FIB scan rotation
			data.fibRot = s.readDouble();

			// fseek(fid,640,'bof'); FIBSEMData.FIBAlnX = fread(fid,1,'double'); % FIB aperture alignment X
			data.fibAlnX = s.readDouble();

			// fseek(fid,648,'bof'); FIBSEMData.FIBAlnY = fread(fid,1,'double'); % FIB aperture alignment Y
			data.fibAlnY = s.readDouble();

			// fseek(fid,656,'bof'); FIBSEMData.FIBStiX = fread(fid,1,'double'); % FIB stigmation X
			data.fibStiX = s.readDouble();

			// fseek(fid,664,'bof'); FIBSEMData.FIBStiY = fread(fid,1,'double'); % FIB stigmation Y
			data.fibStiY = s.readDouble();

			// fseek(fid,672,'bof'); FIBSEMData.FIBShiftX = fread(fid,1,'double'); % FIB beam shift X in micron
			data.fibShiftX = s.readDouble();

			// fseek(fid,680,'bof'); FIBSEMData.FIBShiftY = fread(fid,1,'double'); % FIB beam shift Y in micron
			data.fibShiftY = s.readDouble();

			s.skip( 700 - 688 );

			// fseek(fid,700,'bof'); FIBSEMData.DetC = fread(fid,20,'*char')'; % Name of detector C
			tmp = new byte[ 20 ];
			s.read( tmp );
			data.detectorC = new String( tmp );

			// fseek(fid,720,'bof'); FIBSEMData.DetD = fread(fid,20,'*char')'; % Name of detector D
			s.read( tmp );
			data.detectorD = new String( tmp );

			s.skip( 800 - 740 );
		}
		else
		{
		    // fseek(fid,380,'bof'); FIBSEMData.DetA = fread(fid,10,'*char')'; % Name of detector A
			tmp = new byte[ 10 ];
			s.read( tmp );
			data.detectorA = new String( tmp );

			// fseek(fid,390,'bof'); FIBSEMData.DetB = fread(fid,18,'*char')'; % Name of detector B
			tmp = new byte[ 18 ];
			s.read( tmp );
			data.detectorB = new String( tmp );

			s.skip( 410 - 408 );

			// fseek(fid,410,'bof'); FIBSEMData.DetC = fread(fid,20,'*char')'; % Name of detector C
			tmp = new byte[ 20 ];
			s.read( tmp );
			data.detectorC = new String( tmp );

			// fseek(fid,430,'bof'); FIBSEMData.DetD = fread(fid,20,'*char')'; % Name of detector D
			s.read( tmp );
			data.detectorD = new String( tmp );

			s.skip( 460 - 450 );

			// fseek(fid,460,'bof'); FIBSEMData.Mag = fread(fid,1,'single'); % Magnification
			data.magnification = s.readFloat();

			// fseek(fid,464,'bof'); FIBSEMData.PixelSize = fread(fid,1,'single'); % Pixel size in nm
			data.pixelSize = s.readFloat();

			// fseek(fid,468,'bof'); FIBSEMData.WD = fread(fid,1,'single'); % Working distance in mm
			data.wd = s.readFloat();

			// fseek(fid,472,'bof'); FIBSEMData.EHT = fread(fid,1,'single'); % EHT in kV
			data.eht = s.readFloat();

			s.skip( 480 - 476 );

			// fseek(fid,480,'bof'); FIBSEMData.SEMApr = fread(fid,1,'uint8'); % SEM aperture number
			data.semApr = getUnsignedByte( s.readByte() );

			// fseek(fid,481,'bof'); FIBSEMData.HighCurrent = fread(fid,1,'uint8'); % high current mode (1=on, 0=off)
			data.highCurrent = getUnsignedByte( s.readByte() );

			s.skip( 490 - 482 );

			// fseek(fid,490,'bof'); FIBSEMData.SEMCurr = fread(fid,1,'single'); % SEM probe current in A
			data.semCurr = s.readFloat();

			// fseek(fid,494,'bof'); FIBSEMData.SEMRot = fread(fid,1,'single'); % SEM scan roation in degree
			data.semRot = s.readFloat();

			// fseek(fid,498,'bof'); FIBSEMData.ChamVac = fread(fid,1,'single'); % Chamber vacuum
			data.chamVac = s.readFloat();

			// fseek(fid,502,'bof'); FIBSEMData.GunVac = fread(fid,1,'single'); % E-gun vacuum
			data.gunVac = s.readFloat();

			s.skip( 510 - 506 );

			// fseek(fid,510,'bof'); FIBSEMData.SEMShiftX = fread(fid,1,'single'); % SEM beam shift X
			data.semShiftX = s.readFloat();

			// fseek(fid,514,'bof'); FIBSEMData.SEMShiftY = fread(fid,1,'single'); % SEM beam shift Y
			data.semShiftY = s.readFloat();

			// fseek(fid,518,'bof'); FIBSEMData.SEMStiX = fread(fid,1,'single'); % SEM stigmation X
			data.semStiX = s.readFloat();

			// fseek(fid,522,'bof'); FIBSEMData.SEMStiY = fread(fid,1,'single'); % SEM stigmation Y
			data.semStiY = s.readFloat();

			// fseek(fid,526,'bof'); FIBSEMData.SEMAlnX = fread(fid,1,'single'); % SEM aperture alignment X
			data.semAlnX = s.readFloat();

			// fseek(fid,530,'bof'); FIBSEMData.SEMAlnY = fread(fid,1,'single'); % SEM aperture alignment Y
			data.semAlnY = s.readFloat();

			// fseek(fid,534,'bof'); FIBSEMData.StageX = fread(fid,1,'single'); % Stage position X in mm
			data.stageX = s.readFloat();

			// fseek(fid,538,'bof'); FIBSEMData.StageY = fread(fid,1,'single'); % Stage position Y in mm
			data.stageY = s.readFloat();

			// fseek(fid,542,'bof'); FIBSEMData.StageZ = fread(fid,1,'single'); % Stage position Z in mm
			data.stageZ = s.readFloat();

			// fseek(fid,546,'bof'); FIBSEMData.StageT = fread(fid,1,'single'); % Stage position T in degree
			data.stageT = s.readFloat();

			// fseek(fid,550,'bof'); FIBSEMData.StageR = fread(fid,1,'single'); % Stage position R in degree
			data.stageR = s.readFloat();

			// fseek(fid,554,'bof'); FIBSEMData.StageM = fread(fid,1,'single'); % Stage position M in mm
			data.stageM = s.readFloat();

			s.skip( 560 - 558 );

			// fseek(fid,560,'bof'); FIBSEMData.BrightnessA = fread(fid,1,'single'); % Detector A brightness (%)
			data.brightnessA = s.readFloat();

			// fseek(fid,564,'bof'); FIBSEMData.ContrastA = fread(fid,1,'single'); % Detector A contrast (%)
			data.contrastA = s.readFloat();

			// fseek(fid,568,'bof'); FIBSEMData.BrightnessB = fread(fid,1,'single'); % Detector B brightness (%)
			data.brightnessB = s.readFloat();

			// fseek(fid,572,'bof'); FIBSEMData.ContrastB = fread(fid,1,'single'); % Detector B contrast (%)
			data.contrastB = s.readFloat();

			s.skip( 600 - 576 );

			// fseek(fid,600,'bof'); FIBSEMData.Mode = fread(fid,1,'uint8'); % FIB mode: 0=SEM, 1=FIB, 2=Milling, 3=SEM+FIB, 4=Mill+SEM, 5=SEM Drift Correction, 6=FIB Drift Correction, 7=No Beam, 8=External, 9=External+SEM
			data.mode = getUnsignedByte( s.readByte() );

			s.skip( 604 - 601 );

			// fseek(fid,604,'bof'); FIBSEMData.FIBFocus = fread(fid,1,'single'); % FIB focus in kV
			data.fibFocus = s.readFloat();

			// fseek(fid,608,'bof'); FIBSEMData.FIBProb = fread(fid,1,'uint8'); % FIB probe number
			data.fibProb = getUnsignedByte( s.readByte() );

			s.skip( 620 - 609 );

			// fseek(fid,620,'bof'); FIBSEMData.FIBCurr = fread(fid,1,'single'); % FIB emission current
			data.fibCurr = s.readFloat();

			// fseek(fid,624,'bof'); FIBSEMData.FIBRot = fread(fid,1,'single'); % FIB scan rotation
			data.fibRot = s.readFloat();

			// fseek(fid,628,'bof'); FIBSEMData.FIBAlnX = fread(fid,1,'single'); % FIB aperture alignment X
			data.fibAlnX = s.readFloat();

			// fseek(fid,632,'bof'); FIBSEMData.FIBAlnY = fread(fid,1,'single'); % FIB aperture alignment Y
			data.fibAlnY = s.readFloat();

			// fseek(fid,636,'bof'); FIBSEMData.FIBStiX = fread(fid,1,'single'); % FIB stigmation X
			data.fibStiX = s.readFloat();

			// fseek(fid,640,'bof'); FIBSEMData.FIBStiY = fread(fid,1,'single'); % FIB stigmation Y
			data.fibStiY = s.readFloat();

			// fseek(fid,644,'bof'); FIBSEMData.FIBShiftX = fread(fid,1,'single'); % FIB beam shift X in micron
			data.fibShiftX = s.readFloat();

			// fseek(fid,648,'bof'); FIBSEMData.FIBShiftY = fread(fid,1,'single'); % FIB beam shift Y in micron
			data.fibShiftY = s.readFloat();
		}

		if ( data.fileVersion >= 5 && data.fileVersion <= 8 )
		{
			// fseek(fid,652,'bof'); FIBSEMData.MillingXResolution = fread(fid,1,'uint32'); % FIB milling X resolution
			data.millingXResolution = getUnsignedInt( s.readInt() );
			// fseek(fid,656,'bof'); FIBSEMData.MillingYResolution = fread(fid,1,'uint32'); % FIB milling Y resolution
			data.millingYResolution = getUnsignedInt( s.readInt() );
			// fseek(fid,660,'bof'); FIBSEMData.MillingXSize = fread(fid,1,'single'); % FIB milling X size (um)
			data.millingXSize = s.readFloat();
			// fseek(fid,664,'bof'); FIBSEMData.MillingYSize = fread(fid,1,'single'); % FIB milling Y size (um)
			data.millingYSize = s.readFloat();
			// fseek(fid,668,'bof'); FIBSEMData.MillingULAng = fread(fid,1,'single'); % FIB milling upper left inner angle (deg)
			data.millingULAng = s.readFloat();
			// fseek(fid,672,'bof'); FIBSEMData.MillingURAng = fread(fid,1,'single'); % FIB milling upper right inner angle (deg)
			data.millingURAng = s.readFloat();
			// fseek(fid,676,'bof'); FIBSEMData.MillingLineTime = fread(fid,1,'single'); % FIB line milling time (s)
			data.millingLineTime = s.readFloat();
			// fseek(fid,680,'bof'); FIBSEMData.FIBFOV = fread(fid,1,'single'); % FIB FOV (um)
			data.fibFOV = s.readFloat();
			// fseek(fid,684,'bof'); FIBSEMData.MillingLinesPerImage = fread(fid,1,'uint16'); % FIB milling lines per image
			data.millingLinesPerImage = getUnsignedShort( s.readShort() );
			// fseek(fid,686,'bof'); FIBSEMData.MillingPIDOn = fread(fid,1,'uint8'); % FIB milling PID on
			data.millingPIDOn = getUnsignedByte( s.readByte() );
			s.skip(689 - 687);
			// fseek(fid,689,'bof'); FIBSEMData.MillingPIDMeasured = fread(fid,1,'uint8'); % FIB milling PID measured (0:specimen, 1:beamdump)
			data.millingPIDMeasured = getUnsignedByte( s.readByte() );
			// fseek(fid,690,'bof'); FIBSEMData.MillingPIDTarget = fread(fid,1,'single'); % FIB milling PID target
			data.millingPIDTarget = s.readFloat();
			// fseek(fid,694,'bof'); FIBSEMData.MillingPIDTargetSlope = fread(fid,1,'single'); % FIB milling PID target slope
			data.millingPIDTargetSlope = s.readFloat();
			// fseek(fid,698,'bof'); FIBSEMData.MillingPIDP = fread(fid,1,'single'); % FIB milling PID P
			data.millingPIDP = s.readFloat();
			// fseek(fid,702,'bof'); FIBSEMData.MillingPIDI = fread(fid,1,'single'); % FIB milling PID I
			data.millingPIDI = s.readFloat();
			// fseek(fid,706,'bof'); FIBSEMData.MillingPIDD = fread(fid,1,'single'); % FIB milling PID D
			data.millingPIDD = s.readFloat();

			s.skip(800 - 710);

			// fseek(fid,800,'bof'); FIBSEMData.MachineID = fread(fid,30,'*char')'; % Machine ID
			tmp = new byte[ 30 ];
			s.read( tmp );
			data.machineID = new String( tmp );

			s.skip(850 - 830);

			if ( data.fileVersion == 6 || data.fileVersion == 7 ) {
				// fseek(fid,850,'bof'); FIBSEMData.Temperature = fread(fid,1,'single'); % Temperature (F)
				data.temperature = s.readFloat();
				// fseek(fid,854,'bof'); FIBSEMData.FaradayCupI = fread(fid,1,'single'); % Faraday cup current (nA)
				data.faradayCupI = s.readFloat();
				// fseek(fid,858,'bof'); FIBSEMData.FIBSpecimenI = fread(fid,1,'single'); % FIB specimen current (nA)
				data.fibSpecimenI = s.readFloat();
				// fseek(fid,862,'bof'); FIBSEMData.BeamDump1I = fread(fid,1,'single'); % Beam dump 1 current (nA)
				data.beamDumpI = s.readFloat();
				// fseek(fid,866,'bof'); FIBSEMData.SEMSpecimenI = fread(fid,1,'single'); % SEM specimen current (nA)
				// TODO: Do not know why this is duplicated with the read from 980.
				data.semSpecimenI = s.readFloat();
				// fseek(fid,870,'bof'); FIBSEMData.MillingYVoltage = fread(fid,1,'single'); % Milling Y voltage (V)
				data.millingYVoltage = s.readFloat();
				// fseek(fid,874,'bof'); FIBSEMData.FocusIndex = fread(fid,1,'single'); % Focus index
				data.focusIndex = s.readFloat();
				// fseek(fid,878,'bof'); FIBSEMData.FIBSliceNum = fread(fid,1,'uint32'); % FIB slice #
				data.fibSliceNum = getUnsignedInt( s.readInt() );
			} else {
				s.skip(882 - 850);
			}

			if ( data.fileVersion == 8 ) {
				// fseek(fid,882,'bof'); FIBSEMData.BeamDump2I = fread(fid,1,'single'); % Beam dump 2 current (nA)
				data.beamDump2I = s.readFloat();
				// fseek(fid,886,'bof'); FIBSEMData.MillingI = fread(fid,1,'single'); % Milling current (nA)
				data.millingI = s.readFloat();

				s.skip(980 - 890);
			} else {
				s.skip(980 - 882);
			}

			//	fseek(fid,980,'bof'); FIBSEMData.SEMSpecimenI = fread(fid,1,'single'); % SEM specimen current (nA)
			data.semSpecimenI = s.readFloat();

			s.skip(1000 - 984);

		} else {
			s.skip( 800 - 652 );

			// fseek(fid,800,'bof'); FIBSEMData.MachineID = fread(fid,160,'*char')'; % Read in Machine ID
			tmp = new byte[ 160 ];
			s.read( tmp );
			data.machineID = new String( tmp );

			s.skip( 1000 - 960 );
		}

		// fseek(fid,1000,'bof'); FIBSEMData.FileLength = fread(fid,1,'int64'); % Read in file length in bytes
		data.fileLength = s.readLong();

		s.skip( 1024 - 1008 );

		return data;
	}

	public class FIBSEMData
	{
		/* the magic number identifying the file, should be 3555587570 */
		public long magicNumber;
		/* the version of the file, supported until now is 1,2,3 */
		public int fileVersion;
		/* file type, 1 is Zeiss Neon detectors */
		public int fileType;
		/* date string for the software version */
		public String swDate;
		/* AI sampling time (including oversampling) in seconds */
		public double timeStep;
		/* the number of channels */
		public int numChannels;
		/* 8-bit data switch */
		public int eightBit;
		/* the parameters required to transform the 16 bit signed signal back to volts:
		 * volts = offset + intensity*gain
		 * (we ignore second and third order as they are zero anyways)
		 */
		public float[] offset, gain, secondOrder, thirdOrder;
		/* number of pixels in x per channel */
		public long xRes;
		/* number of pixels in y per channel */
		public long yRes;
		/* AI oversampling */
		public int oversampling;
		/* Read AI delay (# of samples) - only v3*/
		public int AIdelay = 0;
		/* Scan speed (Zeiss #) */
		public int zeissScanSpeed;

	    /* Actual AO (scanning) rate */
		public double scanRate;
		/* Frameline rampdown ratio */
		public double framelineRampdownRatio;
		/* X coil minimum voltage */
		public double xMin;
		/* X coil maximum voltage */
		public double xMax;
		/* Detector minimum voltage */
		public double detMin;
		/* Detector maximum voltage */
		public double detMax;
		/* Decimating factor */
		public int decimatingFactor;

		/* AI Ch1 */
		public int AI1;
		/* AI Ch2 */
		public int AI2;
		/* AI Ch3 */
		public int AI3;
		/* AI Ch4 */
		public int AI4;

		/* notes */
		public String notes;

		/* Name of detector A */
		public String detectorA = "";
		/* Name of detector B */
		public String detectorB = "";
		/* Name of detector C */
		public String detectorC = "";
		/* Name of detector D */
		public String detectorD = "";

		/* Magnification */
		public double magnification;
		/* Pixel size in nm */
		public double pixelSize;
		/* Working distance in mm */
		public double wd;
		/* EHT in kV */
		public double eht;
		/* SEM aperture number */
		public int semApr;
		/* high current mode (1=on, 0=off) */
		public int highCurrent;
		/* FIB mode: 0=SEM, 1=FIB, 2=Milling, 3=SEM+FIB, 4=Mill+SEM, 5=SEM Drift Correction, 6=FIB Drift Correction, 7=No Beam, 8=External, 9=External+SEM */
		public int mode;

		/* SEM probe current in A */
		public double semCurr;
		/* SEM scan roation in degree */
		public double semRot;
		/* Chamber Vacuum */
		public double chamVac;
		/* Gun vacuum */
		public double gunVac;
		/* SEM beam shift X */
		public double semShiftX;
		/* SEM beam shift Y */
		public double semShiftY;
		/* SEM stigmation X */
		public double semStiX;
		/* SEM stigmation Y */
		public double semStiY;
		/* SEM aperture alignment X */
		public double semAlnX;
		/* SEM aperture alignment Y */
		public double semAlnY;
		/* Stage position X in mm */
		public double stageX;
		/* Stage position Y in mm */
		public double stageY;
		/* Stage position Z in mm */
		public double stageZ;
		/* Stage position T in degree */
		public double stageT;
		/* Stage position R in degree */
		public double stageR;
		/* Stage position M in mm */
		public double stageM;
		/* Detector A brightness (%) */
		public double brightnessA;
		/* Detector A contrast (%) */
		public double contrastA;
		/* Detector B brightness (%) */
		public double brightnessB;
		/* Detector B contrast (%) */
		public double contrastB;
		/* FIB focus in kV */
		public double fibFocus;
		/* FIB probe number */
		public int fibProb;
		/* FIB emission current */
		public double fibCurr;
		/* FIB scan rotation */
		public double fibRot;
		/* FIB aperture alignment X */
		public double fibAlnX;
		/* FIB aperture alignment Y */
		public double fibAlnY;
		/* FIB stigmation X */
		public double fibStiX;
		/* FIB stigmation Y */
		public double fibStiY;
		/* FIB beam shift X in micron */
		public double fibShiftX;
		/* FIB beam shift Y in micron */
		public double fibShiftY;

		/* FIB milling X resolution */
		public long millingXResolution;
		/* FIB milling Y resolution */
		public long millingYResolution;
		/* FIB milling X size (um) */
		public float millingXSize;
		/* FIB milling Y size (um) */
		public float millingYSize;
		/* FIB milling upper left inner angle (deg) */
		public float millingULAng;
		/* FIB milling upper right inner angle (deg) */
		public float millingURAng;
		/* FIB line milling time (s) */
		public float millingLineTime;
		/* FIB FOV (um) */
        public float fibFOV;
        /* FIB milling lines per image */
		public int millingLinesPerImage;
		/* FIB milling PID on */
		public int millingPIDOn;
		/* FIB milling PID measured (0:specimen, 1:beamdump) */
		public int millingPIDMeasured;
		/* FIB milling PID target */
		public float millingPIDTarget;
		/* FIB milling PID target slope */
		public float millingPIDTargetSlope;
		/* FIB milling PID P */
		public float millingPIDP;
		/* FIB milling PID I */
		public float millingPIDI;
		/* FIB milling PID D */
		public float millingPIDD;

		/* Temperature (F) */
		public float temperature;
		/* Faraday cup current (nA) */
		public float faradayCupI;
		/* FIB specimen current (nA) */
		public float fibSpecimenI;
		/* Beam dump 1 current (nA) */
		public float beamDumpI;
		/* SEM specimen current (nA) */
		public float semSpecimenI;
		/* Milling Y voltage (V) */
		public float millingYVoltage;
		/* Focus index */
		public float focusIndex;
		/* FIB slice # */
		public long fibSliceNum;

		/* Beam dump 2 current (nA) */
		public float beamDump2I;
		/* Milling current (nA) */
		public float millingI;

		/* name of the machine */
		public String machineID;
		/* file length in bytes */
		public long fileLength;

		public String toString()
		{
			String offsetString = "";
			String gainString = "";
			String secondOrderString = "";
			String thirdOrderString = "";

			for ( int c = 0; c < numChannels; ++c )
				offsetString += "offset channel " + c + " = " + offset[ c ] + "\n";

			for ( int c = 0; c < numChannels; ++c )
				gainString += "gain channel " + c + " = " + gain[ c ] + "\n";

			for ( int c = 0; c < numChannels; ++c )
				secondOrderString += "2nd order channel " + c + " = " + secondOrder[ c ] + "\n";

			for ( int c = 0; c < numChannels; ++c )
				thirdOrderString += "3rd order channel " + c + " = " + thirdOrder[ c ] + "\n";

			return "magic number, should be 3555587570 = " + magicNumber + "\n" +
				   "file version = " + fileVersion + "\n" +
				   "file type, 1 is Zeiss Neon detectors = " + fileType + "\n" +
				   "SW date = " + swDate + "\n" +
				   "AI sampling time (including oversampling) in seconds = " + timeStep + "\n" +
				   "number of channels = " + numChannels + "\n" +
				   "eight bit = " + eightBit + "\n" +
				   offsetString +
				   gainString +
				   secondOrderString +
				   thirdOrderString +
				   "x resolution = " + xRes + "\n" +
				   "y resolution = " + yRes + "\n" +
				   "oversampling = " + oversampling  + "\n" +
				   "AI delay (# of samples) = " + AIdelay + "\n" +
				   "Scan speed (Zeiss #) = " + zeissScanSpeed + "\n" +
				   "Actual AO (scanning) rate = " + scanRate  + "\n" +
				   "Frameline rampdown ratio = " + framelineRampdownRatio  + "\n" +
				   "X coil minimum voltage = " + xMin + "\n" +
				   "X coil maximum voltage = " + xMax + "\n" +
				   "Detector minimum voltage = " + detMin + "\n" +
				   "Detector maximum voltage = " + detMax + "\n" +
				   "decimating factor = " + decimatingFactor + "\n" +
				   "AI Ch1 = " + AI1 + "\n" +
				   "AI Ch2 = " + AI2 + "\n" +
				   "AI Ch3 = " + AI3 + "\n" +
				   "AI Ch4 = " + AI4 + "\n" +
				   "notes = " + notes + "\n" +
				   "detector A = " + detectorA + "\n" +
				   "detector B = " + detectorB + "\n" +
				   "detector C = " + detectorC + "\n" +
				   "detector D = " + detectorD + "\n" +
				   "magnification = " + magnification  + "\n" +
				   "Pixel size in nm = " + pixelSize + "\n" +
				   "Working distance in mm = " + wd + "\n" +
				   "EHT in kV = " + eht + "\n" +
				   "SEM aperture number = " + semApr + "\n" +
				   "high current mode (1=on, 0=off) = " + highCurrent + "\n" +
				   "FIB mode: 0=SEM, 1=FIB, 2=Milling, 3=SEM+FIB, 4=Mill+SEM, 5=SEM Drift Correction, 6=FIB Drift Correction, 7=No Beam, 8=External, 9=External+SEM = " + mode + "\n" +
				   "SEM probe current in A = " + semCurr + "\n" +
				   "SEM scan roation in degree = "+ semRot + "\n"+
				   "Chamber vacuum = "+ chamVac + "\n"+
				   "Gun vacuum = "+ gunVac + "\n"+
				   "SEM beam shift X = " + semShiftX + "\n" +
				   "SEM beam shift Y = " + semShiftY + "\n" +
				   "SEM stigmation X = "+ semStiX + "\n"+
				   "SEM stigmation Y = "+ semStiY + "\n"+
				   "SEM aperture alignment X = "+ semAlnX + "\n"+
				   "SEM aperture alignment Y = "+ semAlnY + "\n"+
				   "Stage position X in mm = "+ stageX + "\n"+
				   "Stage position Y in mm = "+ stageY + "\n"+
				   "Stage position Z in mm = "+ stageZ + "\n"+
				   "Stage position T in degree = "+ stageT + "\n"+
				   "Stage position R in degree = "+ stageR + "\n"+
				   "Stage position M in mm = "+ stageM + "\n"+
				   "Detector A brightness (%) = "+ brightnessA + "\n"+
				   "Detector A contrast (%) = "+ contrastA + "\n"+
				   "Detector B brightness (%) = "+ brightnessB + "\n"+
				   "Detector B contrast (%) = "+ contrastB + "\n"+
				   "FIB focus in kV = "+ fibFocus + "\n"+
				   "FIB probe number = "+ fibProb + "\n"+
				   "FIB emission current = "+ fibCurr + "\n"+
				   "FIB scan rotation = "+ fibRot + "\n"+
				   "FIB aperture alignment X = "+ fibAlnX + "\n"+
				   "FIB aperture alignment Y = "+ fibAlnY + "\n"+
				   "FIB stigmation X = "+ fibStiX + "\n"+
				   "FIB stigmation Y = "+ fibStiY + "\n"+
				   "FIB beam shift X in micron = "+ fibShiftX + "\n"+
				   "FIB beam shift Y in micron = "+ fibShiftY + "\n"+
				   "Milling X resolution = "+ millingXResolution + "\n" +
				   "Milling Y resolution = "+ millingYResolution + "\n" +
				   "Milling X size = "+ millingXSize + "\n" +
				   "Milling Y size = "+ millingYSize + "\n" +
				   "Milling upper left inner angle in degree = "+ millingULAng + "\n" +
				   "Milling upper right inner angle in degree = "+ millingURAng + "\n" +
				   "Milling line time in second = "+ millingLineTime + "\n" +
				   "FIB FOV in micron = "+ fibFOV + "\n" +
				   "Milling lines per image = "+ millingLinesPerImage + "\n" +
				   "Milling PID on = "+ millingPIDOn + "\n" +
				   "Milling PID measured: 0=specimen, 1=beamdump = "+ millingPIDMeasured + "\n" +
				   "Milling PID target = "+ millingPIDTarget + "\n" +
				   "Milling PID target slope = "+ millingPIDTargetSlope + "\n" +
				   "Milling PID P = "+ millingPIDP + "\n" +
				   "Milling PID I = "+ millingPIDI + "\n" +
				   "Milling PID D = "+ millingPIDD + "\n" +
				   "Temperature in Fahrenheit = "+ temperature + "\n" +
				   "Faraday cup current in nA = "+ faradayCupI + "\n" +
				   "FIB specimen current in nA = "+ fibSpecimenI + "\n" +
				   "Beam dump 1 current in nA = "+ beamDumpI + "\n" +
				   "Beam dump 2 current in nA = "+ beamDump2I + "\n" +
				   "SEM specimen current in nA = "+ semSpecimenI + "\n" +
				   "Milling Y voltage = "+ millingYVoltage + "\n" +
				   "Focus index = "+ focusIndex + "\n" +
				   "FIB slice number = "+ fibSliceNum + "\n" +
				   "Milling current in nA = "+ millingI + "\n" +
				   "Machine id = " + machineID  + "\n" +
				   "file length = " + fileLength;
		}
	}

	public static long getUnsignedInt( final int signedInt ) { return signedInt & 0xffffffffL; }
	public static int getUnsignedShort( final short signedShort ) { return signedShort & 0xffff; }
	public static int getUnsignedByte( final byte signedByte ) { return signedByte & 0xff; }

	public static void main( String args[] )
	{
		new ImageJ();

		FIBSEM_Reader.openAsFloat = false;
		FIBSEM_Reader.openAsRaw = false;

		FIBSEM_Reader r = new FIBSEM_Reader();
		//r.run( "/Users/preibischs/Desktop/Zeiss_12-02-07_094618.dat" );
		r.run( "/Users/preibischs/Desktop/Zeiss_12-01-14_210123.dat" );
		//r.run( "/Users/preibischs/Desktop/Zeiss_11-11-03_000019.dat" );

		System.out.println( r.getHeader() );
	}
}
