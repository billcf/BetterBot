
public class AnalysisThread extends Thread
{
	int index;
	int mode;

	public AnalysisThread(int modeSelect, int indexSelect) throws Exception
	{
		index = indexSelect;
		mode = modeSelect;
	}

	public void run()
	{
		if (mode == 0) //RMS Calculation
		{
			if (index == 0)
			{
				BetterBot.s.RMSL = BetterBot.s.RMS(0);
			}
			else
			{
				BetterBot.s.RMSR = BetterBot.s.RMS(1);
			}
		}
		else //EQ Analysis
		{
			BetterBot.s.analyzeBand(index);
		}
	}
}
