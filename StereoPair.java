import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

public class StereoPair
{
	private static final float AMP_DB = 8.65617025f;
	private static final float SILENT_THRESHOLD = 0.02f;
	private static final float MAX_VOLUME = (float) Math.pow(2,-0.2/6);
	public static enum FilterType { LOWPASS, HIPASS };
	private static final int LEFT = 0;
	private static final int RIGHT = 1;

	private static final float RMS_TARGET = StereoPair.dbToAmp(-21.0f);

	private static final float EQ_TOLERANCE = 0.02f;  // If band multiplier less than this, don't actually apply EQ.

	private static final float MAX_BAND_GAIN = 1.5f;
	private static final float MIN_BAND_GAIN = 0.5f;
														//VOCAL ROOT		MUD				RADIO		SENSITIVE	SILBILANCE	AIR
	public static final float[] BANDS_HZ =				{ 150.f,			400.0f,			1500.0f,	4000.0f,	8000.0f,	14000.0f	};
	public static final float[] BANDS_Q =				{ 1.0f,				1.0f,			1.0f,		1.0f,		1.0f,		1.0f		};

	//public static final float[] BANDS_TARGET_RATIO =	{ 0.51586f,			0.66732f,		0.45527f,	0.25961f,	0.16588f,	0.08076f	};
	public static final float[] BANDS_TARGET_RATIO =	{ 0.5f,				0.5f,			0.35f,		0.35f,		0.15f,		0.08076f	};

	float[][] channel;
	int processStart, processEnd;
	AudioFileFormat.Type fileType;

	float RMSL, RMSR;
	float[] bandMult = new float[BANDS_HZ.length];
	float[] bandRMS = new float[BANDS_HZ.length];
	float overallGainFactor = 1.0f;

	AudioFormat format;

	public StereoPair(String fileName) throws UnsupportedAudioFileException, IOException, Exception
	{
		log("Loading file: "+fileName);

		if (fileName.toUpperCase().indexOf(".WAV") > 0)
		{
			fileType = AudioFileFormat.Type.WAVE;
		}
		else if (fileName.toUpperCase().indexOf(".AIF") > 0)
		{
			fileType = AudioFileFormat.Type.AIFF;
		}
		else
		{
			throw new Exception("Unsupported file format.");
		}
		log("File format="+fileType);

		// Get input file as audio stream
		File inputFile = new File(fileName);
		AudioInputStream ais = AudioSystem.getAudioInputStream(inputFile);
		format = ais.getFormat();

		// Create buffer to hold bytes from clip
		int bytesPerFrame = ais.getFormat().getFrameSize(); // How many bytes per frame?
		log("Bytes per frame="+bytesPerFrame);
		int totalFrames = (int) ais.getFrameLength();		// How many frames total?
		log("Total frames="+totalFrames);
		int bufferSize = totalFrames * bytesPerFrame;		// Size buffer large enough to hold entire file (wise?)
		log("Buffer size="+bufferSize);
		byte[] audioBytes = new byte[bufferSize];

		// Read bytes from audiostream into buffer
		ais.read(audioBytes,0,bufferSize);

		ByteBuffer bb = ByteBuffer.wrap(audioBytes);
		if (format.isBigEndian())
		{
			log("BIG ENDIAN");
			bb.order(ByteOrder.BIG_ENDIAN);
		}
		else
		{
			log("LITTLE ENDIAN");
			bb.order(ByteOrder.LITTLE_ENDIAN);
		}

		int outputArrayLength = totalFrames;

		log("outputArrayLength="+outputArrayLength);

		channel = new float[2][];
		channel[LEFT] = new float[outputArrayLength];
		channel[RIGHT] = new float[outputArrayLength];
		int leftCount = 0;
		int rightCount = 0;

		int i=0;
		while (bb.hasRemaining())
		{
			short tempS = bb.getShort();
			float tempF = (float) (tempS / 32768.0f);

			// Alternate values from left and right
			if (i % 2 == 0)
			{
				channel[LEFT][leftCount++] = tempF;
			}
			else if (i % 2 == 1)
			{
				channel[RIGHT][rightCount++] = tempF;
			}
			i++;
		}
		log("i="+i);

		processStart = 0;
		processEnd = channel[LEFT].length;

		log("------------------------------------------------");
	}


	public void debugLine(float g)
	{
		int end=secondsToSamples(1);
		for (int i=0;i<end;i++)
		{
			channel[LEFT][i] = g;
			channel[RIGHT][i] = g;
		}
	}


	public void calculateTargetGain()
	{
		// Use whichever channel is louder to calculate
		float RMS = Math.max(RMSL, RMSR);
		log("RMS="+RMS);
		log("RMS TARGET="+RMS_TARGET+" ("+ampToDb(RMS_TARGET)+"db)");
		if (RMS < RMS_TARGET)
		{
			overallGainFactor = RMS_TARGET/RMS;
			log("RMS below target, RMS gainFactor = "+overallGainFactor);
		}
	}


	public void analyzeBand(int i)
	{
		log("Band #"+i+": Analyzing frequency band="+BANDS_HZ[i]);
		bandRMS[i] = bandRMS(BANDS_HZ[i], BANDS_Q[i]);
		log("Band #"+i+": Band RMS="+bandRMS[i]);
	}


	public void calculateBandMultipliers()
	{
		// For analysis, use the louder channel
		float RMS = Math.max(RMSL, RMSR);

		for (int i=0;i<BANDS_HZ.length;i++)
		{
			log("------------------------------------------------");
			log("Calculating band multiplier for band: "+i);

			float actualBandRatio = bandRMS[i] / RMS;

			log("Band ratio actual="+actualBandRatio);
			log("Band ratio target="+BANDS_TARGET_RATIO[i]);

			bandMult[i] = (BANDS_TARGET_RATIO[i] / actualBandRatio);

			log("Band multiplier="+bandMult[i]);
		}
	}


	public void save(String filename) throws IOException, LineUnavailableException, UnsupportedAudioFileException
	{
		int leftCount = 0;
		int rightCount = 0;
		float tempF = 0.0f;
		short tempS = 0;
		ByteBuffer byteBuf = ByteBuffer.allocate((processEnd-processStart)*4);

		if (format.isBigEndian())
			byteBuf.order(ByteOrder.BIG_ENDIAN);
		else
			byteBuf.order(ByteOrder.LITTLE_ENDIAN);

		for (int j=0;j<(processEnd-processStart)*2;j++)
		{
			if (j % 2 == 0)
			{
				tempF = channel[LEFT][processStart+leftCount];
				leftCount++;
			}
			else if (j % 2 == 1)
			{
				tempF = channel[RIGHT][processStart+rightCount];
				rightCount++;
			}
			tempS = (short) (tempF * 32768.0f);
			byteBuf.putShort(tempS);
		}

		log("Saving file: "+ filename);

		AudioFormat outputFormat = new AudioFormat(format.getEncoding(), 44100.0f, 16, 2, 4, 44100.0f, format.isBigEndian());

		InputStream byteArray = new ByteArrayInputStream(byteBuf.array());
		AudioInputStream aisOut = new AudioInputStream(byteArray, outputFormat, (long) (processEnd-processStart));
		AudioSystem.write(aisOut, fileType, new File(filename));
	}


	public void setProcessStart(int x)
	{
		if (x > channel[LEFT].length)
		{
			processStart = 0;
		}
		else
		{
			processStart = x;
		}
	}


	public void setProcessEnd(int x)
	{
		if (x > channel[LEFT].length)
		{
			processEnd = channel[LEFT].length;
		}
		else
		{
			processEnd = x;
		}
	}


	public void log(String s)
	{
		//System.out.println(System.currentTimeMillis()+": "+s);
		System.out.println(s);
	}


	public int samplesToMs(int samples)
	{
		return (int) ((samples/44100.0f)*1000.0f);
	}


	public int msToSamples(int ms)
	{
		return (int) ((ms/1000.0f)*44100.0f);
	}

	public int secondsToSamples(int seconds)
	{
		return (int) (seconds*44100.0f);
	}

	public int minToSamples(int min)
	{
		return (int) (min*60.0f*44100.0f);
	}

	public void addAmbience()
	{
		log("Adding stereo ambience...");

		// If mono, inject delayed signal directly to side channel

		int AMBIENCE_DELAY_TIME = msToSamples(15);
		float AMBIENCE_DELAY_GAIN = 0.125f;

		for (int i=processStart;i<processEnd;i++)
		{
			// Convert to mid/side
			float mid = (channel[LEFT][i] + channel[RIGHT][i]) * 0.5f;
			float side = (channel[LEFT][i] - channel[RIGHT][i]) * 0.5f;

			// Add delayed signal to side channel
			if (i>AMBIENCE_DELAY_TIME)
			{
				float delayedMid = (channel[LEFT][i-AMBIENCE_DELAY_TIME] + channel[RIGHT][i-AMBIENCE_DELAY_TIME]) * 0.5f;
				side = side + (delayedMid*AMBIENCE_DELAY_GAIN);
			}

			// Convert back to L/R
			channel[LEFT][i] = mid + side;
			channel[RIGHT][i] = mid - side;
		}
	}


	public float calcPeakThreshold()
	{
		return calcPeakThreshold(LEFT);	 // TODOD: Take right channel into account?
	}


	private float calcPeakThreshold(int chanNum)
	{
		float sum = 0;
		for (int i=processStart;i<processEnd;i++)
		{
			sum += Math.abs(channel[chanNum][i]);
		}

		float mean = sum / (processEnd-processStart);
		log("mean="+mean);

		float sum2 = 0;
		for (int i=processStart;i<processEnd;i++)
		{
			sum2 += Math.pow(Math.abs(channel[chanNum][i]) - mean, 2);
		}

		float variance = sum2 / (processEnd-processStart);
		log("variance="+variance);

		float standardDeviation = (float) Math.sqrt(variance);
		log("standard deviation="+standardDeviation);

		return mean + (8*standardDeviation);
	}


	public void peakReduce(float threshold)
	{
		peakReduce(threshold, LEFT);
		peakReduce(threshold, RIGHT);
		log("------------------------------------------------");
	}


	public void noiseReduce()
	{
		log("------------------------------------------------");
		log("Processing noise reduction...");

		// Tuneable parameters // TODO: Add to config file
		float threshold = 0.08f;
		int attackTimeMs = 200; // How fast sound fades out
		int releaseTimeMs = 10; // How fast sound fades back in
		float gainFactor = 0.25f;  // 1.0 = total silence, 0.0 = No gain reduction
		int minGapLengthMs = 750;

		int attackTimeSamples = msToSamples(attackTimeMs);
		int releaseTimeSamples = msToSamples(releaseTimeMs);
		int minGapLengthSamples = msToSamples(minGapLengthMs);

		int gapCount = 0;
		for (int i=processStart;i<processEnd;i++)
		{
			if (Math.abs(channel[LEFT][i]) < threshold)
			{
				int startOfGap = i;
				int endOfGap = findSampleAboveThresholdAfter(threshold,i,LEFT);
				int gapLength = endOfGap - startOfGap;

				if (gapLength > minGapLengthSamples)
				{
					gapCount++;

					int startOfAttack = startOfGap;
					int endOfAttack = startOfGap + attackTimeSamples;
					int startOfRelease = endOfGap - releaseTimeSamples;
					int endOfRelease = endOfGap;

					for (int j=startOfGap;j<endOfGap;j++)
					{
						float modifiedGainFactor = gainFactor;
						if (j >= startOfAttack && j <= endOfAttack)
						{
							// Attack Phase
							float percentThroughAttack = (float) (j-startOfAttack)/attackTimeSamples;
							modifiedGainFactor = 1 - (gainFactor*percentThroughAttack);
						}
						else if (j >= startOfRelease && j <= endOfRelease)
						{
							// Release Phase
							float percentThroughRelease = (float) (j-startOfRelease)/releaseTimeSamples;
							modifiedGainFactor = 1 - (gainFactor*(1-percentThroughRelease));
						}
						else
						{
							// Steady state
							modifiedGainFactor = 1 - gainFactor;
						}

						//Apply gain to sample
						channel[LEFT][j] = channel[LEFT][j]*modifiedGainFactor;
						channel[RIGHT][j] = channel[RIGHT][j]*modifiedGainFactor;
					}
				}
				i = endOfGap + 1;
			}
		}
		log("Noise gaps processed="+gapCount);
		log("------------------------------------------------");

	}


	public void phraseDynamics()
	{
		log("------------------------------------------------");
		log("Processing phrase dynamics...");

		boolean debug = true;

		float strength = 1.0f;

		float attackThreshold = RMSL * 0.75f;
		float releaseThreshold = RMSL * 0.4f;
		float targetRMS = RMSL * 2.5f;
		float maxGainFactor = 2.0f;	 // Boost 200% at the most
		float minGainFactor = 0.5f; // Cut to 50% at the most
		int minGapLengthMs = 200;
		int minPhraseLengthMs = 750;

		int minGapLengthSamples = msToSamples(minGapLengthMs);
		int minPhraseLengthSamples = msToSamples(minPhraseLengthMs);

		if (debug)
		{
			// Clear right channel
			for (int i=processStart;i<processEnd;i++)
			{
				channel[RIGHT][i] = 0.0f;
			}
		}

		int previousPhraseEnd = 0;
		float previousPhraseGain = 1.0f;

		for (int i=processStart;i<processEnd;i++)
		{
			int startOfPhrase = findSampleAboveThresholdAfter(attackThreshold,i,LEFT);
			int endOfPhrase = findNextGapAfter(minGapLengthSamples,releaseThreshold,startOfPhrase+minPhraseLengthSamples,LEFT);
			float phraseRMS = RMSAboveNoise(LEFT,startOfPhrase,endOfPhrase,attackThreshold);
			float gainFactor = Math.max(minGainFactor,Math.min(maxGainFactor,targetRMS/phraseRMS));

			// If no signal above noise floor, do not change gain.
			if (phraseRMS == 0.0f) gainFactor = 1.0f;

			if (debug) log(i + ": Phrase: "+ startOfPhrase + "->" + endOfPhrase + " RMS="+phraseRMS + " gain="+gainFactor);

			// Apply strength factor
			if (gainFactor > 1)
			{
				// Positive gain
				gainFactor = ((gainFactor-1))*strength + 1;
			}
			else
			{
				// Negative gain
				gainFactor = 1 - ((1-gainFactor))*strength;
			}


			// Ramp from previous phrase to start of new phrase
			int rampTimeSamples = startOfPhrase-previousPhraseEnd;
			for (int j=previousPhraseEnd;j<startOfPhrase;j++)
			{
				float percentThroughRamp = (float) (j-previousPhraseEnd)/rampTimeSamples;
				float modifiedGainFactor = previousPhraseGain+(percentThroughRamp*(gainFactor-previousPhraseGain));

				//Apply gain to sample
				channel[LEFT][j] = channel[LEFT][j]*modifiedGainFactor;
				channel[RIGHT][j] = channel[RIGHT][j]*modifiedGainFactor;

				if (debug)
				{
					// Show envelope on right channel
					channel[RIGHT][j] = (modifiedGainFactor-1)/(maxGainFactor-minGainFactor);
					if (channel[RIGHT][j] < 0) channel[RIGHT][j] *= 2;
				}
			}

			// Apply gain to this phrase
			for (int j=startOfPhrase;j<endOfPhrase;j++)
			{
				//Apply gain to sample
				channel[LEFT][j] = channel[LEFT][j]*gainFactor;
				channel[RIGHT][j] = channel[RIGHT][j]*gainFactor;

				if (debug)
				{
					// Show envelope on right channel
					channel[RIGHT][j] = (gainFactor-1)/(maxGainFactor-minGainFactor);
					if (channel[RIGHT][j] < 0) channel[RIGHT][j] *= 2;
				}
			}

			previousPhraseEnd = endOfPhrase;
			previousPhraseGain = gainFactor;

			i = endOfPhrase + 1;
		}

		log("------------------------------------------------");
	}


	private int findNextGapAfter(int minGapLength, float threshold, int start, int chanNum)
	{
		for (int i=start;i<processEnd;i++)
		{
			int currentGapEnd = findSampleAboveThresholdAfter(threshold,i,LEFT);
			int currentGapLength = currentGapEnd - i;
			if (currentGapLength > minGapLength)
			{
				return i;
			}
			else
			{
				i = currentGapEnd+1;
			}
		}
		return processEnd;
	}




	private int findSampleAboveThresholdAfter(float threshold, int start, int chanNum)
	{
		int loc = processEnd;
		for (int i=start;i<processEnd;i++)
		{
			if (Math.abs(channel[chanNum][i]) > threshold)
			{
				loc = i;
				break;
			}
		}

		return loc;
	}


	public void logPeaks(float threshold)
	{
		int chanNum=LEFT;
		int MINUMUM_DISTANCE = msToSamples(100);


		log("Begin logging peaks");
		for (int i=processStart;i<processEnd;i++)
		{
			// If sample value above theshold...
			if (Math.abs(channel[chanNum][i]) > threshold)
			{
				// Get position of previous zero-crossing
				int startPeakLoc = findZeroCrossBefore(i,chanNum);

				// Get position of next zero-crossing
				int endPeakLoc = findZeroCrossAfter(i,chanNum);

				// Get position and value of peak top
				int peakTopLoc = findPeakBetween(startPeakLoc,endPeakLoc,chanNum);
				float peakTopValue = channel[chanNum][peakTopLoc];

				int peakTopLocMs = samplesToMs(peakTopLoc);

				log(""+peakTopLocMs);

				// Skip ahead to end of peak
				i=endPeakLoc+MINUMUM_DISTANCE-1;
			}
		}
		log("End logging peaks");
	}


	private void peakReduce(float threshold, int chanNum)
	{
		int peaksReduced = 0;
		for (int i=processStart;i<processEnd;i++)
		{
			// If sample value above theshold...
			if (Math.abs(channel[chanNum][i]) > threshold)
			{
				peaksReduced++;

				// Get position of previous zero-crossing
				int startPeakLoc = findZeroCrossBefore(i,chanNum);

				// Get position of next zero-crossing
				int endPeakLoc = findZeroCrossAfter(i,chanNum);

				// Get position and value of peak top
				int peakTopLoc = findPeakBetween(startPeakLoc,endPeakLoc,chanNum);
				float peakTopValue = channel[chanNum][peakTopLoc];

				// Calculate required gain
				float gainFactor = Math.abs(threshold/peakTopValue);

				// Apply gain to the peak range
				for (int j=startPeakLoc;j<endPeakLoc;j++)
				{
					channel[chanNum][j] = channel[chanNum][j]*gainFactor;
				}

				// Skip ahead to end of peak
				i=endPeakLoc-1;
			}
		}

		log("Peaks reduced="+peaksReduced);
	}


	public int findPeakBetween(int start, int end, int chanNum)
	{
		float max = 0;
		int maxLoc = 0;
		for (int i=start;i<end;i++)
		{
			if (Math.abs(channel[chanNum][i]) > max)
			{
				max = Math.abs(channel[chanNum][i]);
				maxLoc = i;
			}
		}

		return maxLoc;
	}


	public int findZeroCrossBefore(int start, int chanNum)
	{
		int zeroCross = 0;
		for (int i=start-1;i>processStart;i--)
		{
			if (channel[chanNum][i] > 0 && channel[chanNum][i-1] < 0)
			{
				zeroCross = i;
				break;
			}

			if (channel[chanNum][i] < 0 && channel[chanNum][i-1] > 0)
			{
				zeroCross = i;
				break;
			}
		}

		return zeroCross;
	}


	public int findZeroCrossAfter(int start, int chanNum)
	{
		int zeroCross = 0;
		for (int i=start+1;i<processEnd-1;i++)
		{
			if (channel[chanNum][i] > 0 && channel[chanNum][i+1] < 0)
			{
				zeroCross = i;
				break;
			}

			if (channel[chanNum][i] < 0 && channel[chanNum][i+1] > 0)
			{
				zeroCross = i;
				break;
			}
		}

		if (zeroCross == 0) zeroCross=processEnd;

		return zeroCross;
	}


	public void normalize()
	{
		log("Normalizing...");

		float stereoPeak = getPeakLevel();
		float normalizeFactor = Math.abs(MAX_VOLUME/stereoPeak);

		for (int i=processStart;i<processEnd;i++)
		{
			channel[LEFT][i] = channel[LEFT][i]*normalizeFactor;
			channel[RIGHT][i] = channel[RIGHT][i]*normalizeFactor;
		}
	}


	public void gain(float g)
	{
		log("Applying process: Gain: "+g);
		for (int i=processStart;i<processEnd;i++)
		{
			channel[LEFT][i] = channel[LEFT][i]*g;
			channel[RIGHT][i] = channel[RIGHT][i]*g;
		}
	}


	public void gain(float g, int chanNum)
	{
		log("Applying process: Gain");
		for (int i=processStart;i<processEnd;i++)
		{
			channel[chanNum][i] = channel[chanNum][i]*g;
		}
	}


	public float getPeakLevel()
	{
		float largestFloatLeft=0.0f;
		float largestFloatRight=0.0f;
		for (int i=processStart;i<processEnd;i++)
		{
			if (Math.abs(channel[LEFT][i])>largestFloatLeft) largestFloatLeft = Math.abs(channel[LEFT][i]);
			if (Math.abs(channel[RIGHT][i])>largestFloatRight) largestFloatRight = Math.abs(channel[RIGHT][i]);
		}

		float stereoPeak = Math.max(largestFloatLeft, largestFloatRight);
		return stereoPeak;
	}


	public void clipper(float clipAt)
	{
		log("Applying process: Clipper");
		for (int i=processStart;i<processEnd;i++)
		{
			if (channel[LEFT][i] > 0 && channel[LEFT][i]>clipAt) channel[LEFT][i]=clipAt;
			else if (channel[LEFT][i] < 0 && channel[LEFT][i]<-clipAt) channel[LEFT][i]=-clipAt;

			if (channel[RIGHT][i] > 0 && channel[RIGHT][i]>clipAt) channel[RIGHT][i]=clipAt;
			else if (channel[RIGHT][i] < 0 && channel[RIGHT][i]<-clipAt) channel[RIGHT][i]=-clipAt;
		}
	}


	public void jiggle(float g)
	{
		log("Applying process: Jiggle");
		for (int i=processStart;i<processEnd;i++)
		{
			channel[LEFT][i] = channel[LEFT][i] + random(-g/2,g/2);
			channel[RIGHT][i] = channel[RIGHT][i] + random(-g/2,g/2);
		}
	}


	public float random(float min, float max)
	{
		return (float) (min + (Math.random() * ((max - min))));
	}


	public void lowPass(float freqHz, float q)
	{
		filter(freqHz,q,FilterType.LOWPASS);
	}


	public void hiPass(float freqHz, float q)
	{
		filter(freqHz,q,FilterType.HIPASS);
	}


	public void filter(float freqHz, float q, FilterType f)
	{
		float fd0left = 0f, fd0right = 0f, fd1left = 0f, fd1right = 0f, fd2left = 0f, fd2right = 0f;

		float damp = (float) (0.01+q*20);
		float c = (float) (1/Math.tan(Math.PI*freqHz/44100.0f));
		float staticFK = 1 / (1 + c*(c+damp));
		float staticFA1 = 2 * (1 - c*c) * staticFK;
		float staticFA0 = (1 + c*(c-damp)) * staticFK;

		for (int i=processStart;i<processEnd;i++)
		{
			// Calculate value for current slot, based on current sample value and values in previous two slots
			fd0left = (staticFK*channel[LEFT][i]) - (staticFA1*fd1left) - (staticFA0*fd2left);
			fd0right = (staticFK*channel[RIGHT][i]) - (staticFA1*fd1right) - (staticFA0*fd2right);

			// New sample value is sum of current slot plus all previous slots
			if (f == FilterType.HIPASS)
			{
				channel[LEFT][i] = channel[LEFT][i] - (fd0left + fd1left + fd1left + fd2left);
				channel[RIGHT][i] = channel[RIGHT][i] - (fd0right + fd1right + fd1right + fd2right);
			}
			else if (f == FilterType.LOWPASS)
			{
				channel[LEFT][i] = fd0left + fd1left + fd1left + fd2left;
				channel[RIGHT][i] = fd0right + fd1right + fd1right + fd2right;
			}

			// Shift value to next slot
			fd2left = fd1left;
			fd2right = fd1right;

			// Shift value to next slot
			fd1left = fd0left;
			fd1right = fd0right;
		}
	}


	public void channelBalance()
	{
		log("RMS channel[LEFT]="+RMSL);
		log("RMS channel[RIGHT]="+RMSR);

		if (RMSL < SILENT_THRESHOLD && !(RMSR < SILENT_THRESHOLD))
		{
			// channel[LEFT] is basically silent, so copy from channel[RIGHT]
			log("channel[LEFT] is below silent threshold!");
			copyChan(RIGHT,LEFT);
		}
		else if (RMSR < SILENT_THRESHOLD && !(RMSL < SILENT_THRESHOLD))
		{
			// channel[RIGHT] is basically silent, so copy from channel[RIGHT]
			log("Right is below silent threshold!");
			copyChan(LEFT,RIGHT);
		}
		else
		{
			if (RMSR < RMSL)
			{
				// channel[LEFT] is louder
				Float gainFactor = RMSL/RMSR;
				log("Left louder! gain factor (amp) = "+ gainFactor);
				gain(gainFactor,RIGHT);
			}
			else
			{
				// Right is louder
				Float gainFactor = RMSR/RMSL;
				log("Right louder! gain factor (amp) = "+ gainFactor);
				gain(gainFactor,LEFT);
			}
		}
	}


	public static float dbToAmp(float db)
	{
		return (float) Math.exp(db/AMP_DB);
	}


	public static float ampToDb(float amp)
	{
		return (float) (AMP_DB*Math.log(amp));
	}


	public float RMS(int chanNum)
	{
		log("Calculating RMS for channel:"+chanNum);

		float sum = 0.0f;
		for (int i=processStart;i<processEnd;i++)
		{
			sum += Math.pow(channel[chanNum][i],2);
		}

		float tempRMS = (float) Math.sqrt(sum/(processEnd-processStart));
		log("RMS for channel "+ chanNum + "="+tempRMS);
		return tempRMS;
	}

	public float RMSAboveNoise(int chanNum,int start,int end, float noiseFloor)
	{
		float sum = 0.0f;
		int pointsCounted = 0;
		for (int i=start;i<end;i++)
		{
			if (channel[chanNum][i] > noiseFloor)
			{
				pointsCounted++;
				sum += Math.pow(channel[chanNum][i],2);
			}
		}

		if (pointsCounted == 0) pointsCounted++;  // To avoid NaN

		return (float) Math.sqrt(sum/pointsCounted);
	}



	public void copyChan(int fromChan, int toChan)
	{
		for (int i=processStart;i<processEnd;i++)
		{
			channel[toChan][i] = channel[fromChan][i];
		}
	}


	public void print(int start, int end)
	{
		for (int i=start;i<end;i++)
		{
			log(i + "=" + channel[LEFT][i] + " / " + channel[RIGHT][i]);
		}
	}


	public void removeDCOffset()
	{
		float previousL = 0f;
		float previousR = 0f;
		float currentL = 0f;
		float currentR = 0f;

		for (int i=processStart;i<processEnd;i++)
		{
			currentL = 0.999f*currentL + channel[LEFT][i] - previousL;
			previousL = channel[LEFT][i];
			channel[LEFT][i] = currentL;

			currentR = 0.999f*currentR + channel[RIGHT][i] - previousR;
			previousR = channel[RIGHT][i];
			channel[RIGHT][i] = currentR;
		}
	}


	public void limiter(float thresholdDb)
	{
		log("Limiting at threshold: "+thresholdDb);

		double gain = 1;
		double dc = Math.pow(10,-30);
		double currentMaxLevel = 0;
		double thresh = Math.exp(thresholdDb/AMP_DB);
		double t = 0;
		double b = -Math.exp(-62.83185307 / 44100.0f);
		double a = 1.0 + b;

		for (int i=processStart;i<processEnd;i++)
		{
			currentMaxLevel = Math.max(Math.abs(channel[LEFT][i]), Math.abs(channel[RIGHT][i]));
			t = a*currentMaxLevel - b*t + dc;
			currentMaxLevel = Math.max(Math.sqrt(t-dc), currentMaxLevel);
			if (currentMaxLevel > thresh)
			{
				gain = currentMaxLevel;
			}
			else
			{
				gain = thresh;
			}
			channel[LEFT][i] /= gain;
			channel[RIGHT][i] /= gain;
		}
	}


	public void eqBalance()
	{
		for (int i=0;i<BANDS_HZ.length;i++)
		{
			log("----------------------------------------------");
			log("Processing band #"+i);
			log("Band FREQ="+BANDS_HZ[i]);
			bandGain(BANDS_HZ[i], BANDS_Q[i], bandMult[i]);
		}
	}


	public float bandRMS(float freq, float width)
	{
		return bandPass(freq, width, 1.0f, processStart, processEnd);
	}


	public float bandRMSRegion(float freq, float width, int start, int end)
	{
		return bandPass(freq, width, 1.0f, start, end);
	}

	public void bandGain(float freq, float width, float wetGainFactor)
	{
		wetGainFactor = Math.min(Math.max(wetGainFactor,MIN_BAND_GAIN),MAX_BAND_GAIN);

		if (Math.abs(1-wetGainFactor) < EQ_TOLERANCE)
		{
			log("Band gain within tolerance, so skip processing.");
		}
		else
		{
			log("Applying bandGain with gainFactor="+wetGainFactor);
			bandPass(freq, width, wetGainFactor, processStart, processEnd);
		}
	}


	// freq=1-20000, width=0-1 (narrow->wide)
	public float bandPass(float freq, float width, float wetGainFactor, int start, int end)
	{
		float spl0,spl1,d0_l,fk,fa1,fd1_l=0f,fd2_l=0f,fa0,d0_r,fd1_r=0f,fd2_r=0f,dampening,c,a2;

		float sum = 0.0f;

		wetGainFactor = wetGainFactor-1.0f;
		log("Using wetGainFactor="+wetGainFactor);

		dampening=width*0.999f + 0.001f;
		c = (float) ( 1 / Math.tan( Math.PI*freq / 44100.0f ) );
		a2 = 1 + c*(c+dampening);
		fa1 = 2 * (1 - c*c) / a2;
		fa0 = (1 + c*(c-dampening)) / a2;
		fk = c*dampening / a2;

		for (int i=start;i<end;i++)
		{
			spl0 = channel[LEFT][i];
			spl1 = channel[RIGHT][i];
			d0_l = fk*spl0 - (fa1*fd1_l + fa0*fd2_l);
			d0_r = fk*spl1 - (fa1*fd1_r + fa0*fd2_r);
			spl0 = d0_l - fd2_l;
			spl1 = d0_r - fd2_r;
			fd2_l = fd1_l;
			fd2_r = fd1_r;
			fd1_l = d0_l;
			fd1_r = d0_r;

			channel[LEFT][i] = channel[LEFT][i] + spl0*wetGainFactor;
			channel[RIGHT][i] = channel[RIGHT][i] + spl1*wetGainFactor;

			sum += Math.pow(spl0,2);
		}

		return (float) Math.sqrt(sum/(end-start));
	}


	public void boostToTargetRMS()
	{
		if (overallGainFactor > 1.0f)
		{
			gain(overallGainFactor);
			peakReduce(1.0f);
		}
		else
		{
			log("RMS above target.	No gain required.");
		}
		normalize();
	}

}

