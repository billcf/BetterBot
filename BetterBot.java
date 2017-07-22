
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.Map;

import javax.sound.sampled.*;

public class BetterBot
{
	public static StereoPair s;
	public static enum Mode { ANALYSIS, PREVIEW, FULL };

	// Return codes
	private static final int RETURN_OK = 0;
	private static final int RETURN_ERROR = 1;
	private static final int RETURN_TOO_LONG = 2;

	private static final int ANALYZE_START_MIN = 1;	 // Start one minute in, to avoid any jingles
	private static final int ANALYZE_END_MIN = 15; // Stop at minute 15

	private static final int PROCESS_PREVIEW_START_MIN = 0;
	private static final int PROCESS_PREVIEW_END_MIN = 2;

	private static final int PROCESS_FULL_START_MIN = 0;
	private static final int PROCESS_FULL_END_MIN = 60;

	private static final int MAX_LENGTH_MIN = 60;

	private static final int THREAD_TIMEOUT_MS = 120000;  // Timeout for analysis threads
	private static final int RMS_MODE = 0;	// Analysis mode for multithreaded analysis
	private static final int EQ_MODE = 1;	// Analysis mode for multithreaded analysis


	public static void main(String[] args)
	{
		try
		{
			long startTime = System.nanoTime();

			log("************************************************");
			log("PROCESSING ARGUMENTS");
			log("************************************************");

			String inputFile = args[0];
			log("inputFile="+inputFile);

			String outputFile = args[1];
			log("outputFile="+outputFile);

			String modeString = args[2];
			Mode mode = Mode.ANALYSIS;
			if (modeString.equals("analysis"))
			{
				log("Analysis mode!");
				mode = Mode.ANALYSIS;
			}
			else if (modeString.equals("preview"))
			{
				log("Preview mode!");
				mode = Mode.PREVIEW;
			}
			else if (modeString.equals("full"))
			{
				log("Full mode!");
				mode = Mode.FULL;
			}

			log("************************************************");
			log("CONVERSION PHASE");
			log("************************************************");

			log("Converting to 44.1khz, 16-bit, stereo...");

			File conversionFile = new File(inputFile);

			AudioInputStream ais = AudioSystem.getAudioInputStream(conversionFile);

			AudioFileFormat.Type fileType;
			String extension;
			if (inputFile.toUpperCase().indexOf(".WAV") > 0)
			{
				fileType = AudioFileFormat.Type.WAVE;
				extension = ".wav";
			}
			else if (inputFile.toUpperCase().indexOf(".AIF") > 0)
			{
				fileType = AudioFileFormat.Type.AIFF;
				extension = ".aif";
			}
			else
			{
				throw new Exception("Unsupported file type.");
			}

			int originalChannelCount = ais.getFormat().getChannels();
			if (originalChannelCount > 2)
			{
				throw new Exception("Multi-channel audio not supported.");
			}

			boolean originalMono = (originalChannelCount == 1);

			AudioFormat outputFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100.0f, 16, 2, 4, 44100.0f, false);
			String convertedFilename = removeExtension(inputFile) + "-converted" + extension;
			AudioInputStream targetStream = AudioSystem.getAudioInputStream(outputFormat, ais);
			AudioSystem.write(targetStream, fileType, new File(convertedFilename));
			targetStream.close();
			log("Saved as: "+convertedFilename);

			log("************************************************");
			log("ANALYSIS PHASE");
			log("************************************************");

			// Load file and init
			s = new StereoPair(convertedFilename);

			if ((s.channel[0].length) > s.minToSamples(MAX_LENGTH_MIN))
			{
				log("len="+s.channel[0].length);
				log("max="+s.minToSamples(MAX_LENGTH_MIN));
				log("File too long!");
				System.exit(RETURN_TOO_LONG);
			}

			// Normalize first
			s.normalize();

			// Set analysis range
			s.setProcessStart(s.minToSamples(ANALYZE_START_MIN));
			s.setProcessEnd(s.minToSamples(ANALYZE_END_MIN));

			// Start RMS analysis threads running (one for left, one for right)...
			AnalysisThread[] rmsAnalysisThreads = new AnalysisThread[2];
			for (int j=0;j<rmsAnalysisThreads.length;j++)
			{
				rmsAnalysisThreads[j] = new AnalysisThread(RMS_MODE, j);
				rmsAnalysisThreads[j].start();
			}

			// Start EQ analysis threads running (one for each band)...
			AnalysisThread[] eqAnalysisThreads = new AnalysisThread[StereoPair.BANDS_HZ.length];
			for (int j=0;j<eqAnalysisThreads.length;j++)
			{
				eqAnalysisThreads[j] = new AnalysisThread(EQ_MODE, j);
				eqAnalysisThreads[j].start();
			}

			// Join all analysis threads...
			for (int i=0;i<rmsAnalysisThreads.length;i++)
			{
				try
				{
					rmsAnalysisThreads[i].join(THREAD_TIMEOUT_MS);
				}
				catch (InterruptedException ignore)
				{
				}
			}

			for (int i=0;i<eqAnalysisThreads.length;i++)
			{
				try
				{
					eqAnalysisThreads[i].join(THREAD_TIMEOUT_MS);
				}
				catch (InterruptedException ignore)
				{
				}
			}

			log("Multi-threaded analysis complete.");
			log("------------------------------------------------");


			// Calculate targets based on analysis
			s.calculateTargetGain();
			s.calculateBandMultipliers();

			if (mode.equals(Mode.FULL) || mode.equals(Mode.PREVIEW))
			{
				log("************************************************");
				log("PROCESSING PHASE");
				log("************************************************");

				if (mode.equals(Mode.FULL))
				{
					s.setProcessStart(s.minToSamples(PROCESS_FULL_START_MIN));
					s.setProcessEnd(s.minToSamples(PROCESS_FULL_END_MIN));
				}
				else if (mode.equals(Mode.PREVIEW))
				{
					s.setProcessStart(s.minToSamples(PROCESS_PREVIEW_START_MIN));
					s.setProcessEnd(s.minToSamples(PROCESS_PREVIEW_END_MIN));
				}

				s.removeDCOffset();			// Remove DC offset

				s.channelBalance();			// Make sure L/R balanced

				s.eqBalance();				// Adjust EQ as necessary to match target

				s.hiPass(75, 0.1f);		// Remove low rumble
				s.hiPass(75, 0.1f);		// Remove low rumble
				s.hiPass(75, 0.1f);		// Remove low rumble

				s.normalize();

				s.phraseDynamics();

				if (originalMono) s.addAmbience();

				s.boostToTargetRMS();

				s.save(outputFile);
			}


			/////////////////////////////////////////////////////////////////////////////////
			/////////////////////////////////////////////////////////////////////////////////

			// Clean up memory
			s = null;
			System.gc();

			// Stats
			long endTime = System.nanoTime();
			long elapsedTime = endTime - startTime;
			double seconds = (double)elapsedTime / 1000000000.0;
			log("------------------------------------------------");
			log("FINISHED!");
			log("Processing time in seconds = " + seconds);
			log("------------------------------------------------");

			Runtime runtime = Runtime.getRuntime();
			NumberFormat format = NumberFormat.getInstance();
			long maxMemory = runtime.maxMemory();
			long allocatedMemory = runtime.totalMemory();
			long freeMemory = runtime.freeMemory();
			log("free memory: " + format.format(freeMemory / 1024));
			log("allocated memory: " + format.format(allocatedMemory / 1024));
			log("max memory: " + format.format(maxMemory / 1024));
			log("total free memory: " + format.format((freeMemory + (maxMemory - allocatedMemory)) / 1024));
			log("------------------------------------------------");

			System.exit(RETURN_OK);
		}
		catch (Exception e)
		{
			log("Exception!: "+e.getMessage());
			e.printStackTrace();
			System.exit(RETURN_ERROR);
		}
	}


	private static String removeExtension(String str)
	{
		int pos = str.lastIndexOf(".");
		if (pos >= 0)
		{
			return str.substring(0, pos);
		}
		else
		{
			return str;
		}
	}

	
	private static void logClipInfo(Clip clip)
	{
		log("Length (frames)="+clip.getFrameLength());
		log("Position (frames)="+clip.getLongFramePosition());
		log("Length (ms)="+clip.getMicrosecondLength());
		log("Position (ms)="+clip.getMicrosecondPosition());
		log("Level="+clip.getLevel());

		AudioFormat format = clip.getFormat();
		log("Channels="+format.getChannels());
		log("Encoding="+format.getEncoding());
		log("Frame Rate="+format.getFrameRate());
		log("Frame Size="+format.getFrameSize());
		log("Sample rate="+format.getSampleRate());
		log("Sample size="+format.getSampleSizeInBits());
		log("Big Endian?="+format.isBigEndian());

		Map<String, Object> formatProps = format.properties();
		log("Property count="+formatProps.size());
		Iterator<Object> formatPropsValIter = formatProps.values().iterator();
		Iterator<String> formatPropsKeyIter = formatProps.keySet().iterator();
		while (formatPropsKeyIter.hasNext())
		{
			log("Property:"+formatPropsKeyIter.next() + "=" + formatPropsValIter.next().toString());
		}
	}


	private static void play(String fileName) throws LineUnavailableException, UnsupportedAudioFileException, IOException, InterruptedException
	{
		File inputFile = new File(fileName);
		AudioInputStream ais = AudioSystem.getAudioInputStream(inputFile);
		Clip clip = AudioSystem.getClip();
		clip.open(ais);
		logClipInfo(clip);

		log("Playing file: "+fileName);

		// Play the clip
		clip.start();
		log("Playing...");
		Thread.sleep(1000);
		while (clip.isActive())
		{
			Thread.sleep(1000);
		}

		log("Done.");
	}


	public static void log(String s)
	{
		//System.out.println(System.currentTimeMillis()+ ": "+ s);
		System.out.println(s);
	}
}

