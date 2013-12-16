
package embs;

import com.ibm.saguaro.system.*;
import com.ibm.saguaro.logger.*;

/* @author : Y6329127 */
public class Scheduler {

	private static Timer  tlisten;						// responsible for coming back if no listen can be scheduled
	private static Timer  tsrs1;						// responsible for updating the next arrival of source 1
	private static Timer  tsrs2;													
	private static Timer  tsrs3;
	
	// Static Indices
    private static final int SINK 	= 0;
	private static final int CHS1	= 1;
	private static final int CHS2	= 2;
	private static final int CHS3	= 3;
	
	//Relay Timer Indices
    private static final int TSEND	= 1;
    private static final int TSYNC	= 2;
    private static final int TRECV	= 3;
   
    private static final long TIMECONST = 15000;		//Constant minimum distance between events	
    private static final boolean POWER_SAVE = false;	//power save mode
    
    //States
    private static final int CALC_STATE	= 1; 			//Calculating period
    private static final int SYNC_STATE	= 2; 			//Syncing
    private static final int RECV_STATE	= 3; 			//Receiving from sources
	
     //Assumed ordering - SINK, SO1, SO2, SO3
	private static long[] periods 		= new long[] {0,Time.toTickSpan(Time.MILLISECS, 5500),Time.toTickSpan(Time.MILLISECS, 6900),Time.toTickSpan(Time.MILLISECS, 8100)}; 		//periods in ticks
	private static long[] sch_times 	= new long[] {0,0,0,0};						//expected arrival times
	private static int[] successes		= new int[] {0,0,0,0};						//successfully relayed frames per source (meant to be a bit array)
	
	private static long   t_trans		= 0;			//next transmission time
	private static int 	  nbeacons		= 0;			//number of beacon frames
	
    static{
    					
        tlisten  = new Timer();	//Timer - invokes Schedule listen if nothing was scheduled for listening
        tlisten.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
			         Scheduler.ListenAgain(param,time);
                }
            });
            		
		tsrs1  = new Timer(); //Timer - housekeeper 1
        tsrs1.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
			       Scheduler.UpdateCHS1(param,time);
                }
            }); 
            
		tsrs2  = new Timer(); //Timer - housekeeper 2
        tsrs2.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
			        Scheduler.UpdateCHS2(param,time);
                }
            });
         
		tsrs3  = new Timer(); //Timer - housekeeper 3
        tsrs3.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
			         Scheduler.UpdateCHS3(param,time);
                }
            });
    }
    
   //Schedule the next transmission
	public static void ScheduleTransmission()
	{
		RelayNode.GetTimer(TSEND).setAlarmTime(t_trans);					//Set the sending timer of the relay									
		t_trans += (nbeacons + 6) * periods[SINK];							//update transmission time	
		
		LogMessage(csr.s2b(" Scheduled transmission for  "),t_trans);	
	}
	
	/****************************************************************************************************************************************
 	* Summary: Method responsible for scheduling receiving periods from sources.															*							
    * --------------------------------------------------------------------------------------------------------------------------------------*
    * Uses: sch_times (scheduled times) array containing the next expected arrival time of the packet.										*
    * 		sch_times is sorted in ascending order. If expected arrival time is undefined (aka = 0) then a listen until next transmission 	*
    *  		or schedule time is invoked.																									*
    * 																																		*
    * 		If the expected arrival is known then a listen starts TIMECONST time before the arrival time.									*
    * 																																		*
    *  		The method then sets a timer to invoke itself after the scheduled listen time , to schedule another listen.						*
    *  		If it has been called prematurely (happens if a packet is received for example) it cancels its timer alarm and tries to schedule*
    * 		another listen.																													*
    * 																																		*
    *		If no listen can be scheduled, the method will invoke itself after TIMECONST time.												*
    * 																																		*
 	*****************************************************************************************************************************************/
	public static void ScheduleListen()
	{	
		boolean scheduled	= false;																//Check indicating that at least one listen has been scheduled
		long[] sorted		= SortArray(sch_times);													//Array of sources arrival times sorted in ascending order
		
		
		if(tlisten.getAlarmTime() != 0) tlisten.cancelAlarm();
		
		
		if(t_trans < Time.currentTicks()) 															//Error correction. If next transmission time is in the past, increment	it and schedule												
		{
			LogMessage(csr.s2b(" FAILED T-TIME IS IN THE PAST "),(byte)0);
			t_trans += (nbeacons + 6) * periods[SINK];
			ScheduleTransmission();
		}
		int tindex 		= GetIndexFromValue(sch_times,sorted[0]);									//Get source index
		long listentime = 0;
		long alarmtime	= 0;
		if(!POWER_SAVE || RelayNode.GetNonRelayedFrames() < 5)										//If not in powersave or there are less than 5 frames bufferred
		{
			if(sorted[0] == 0)
			{
				if(t_trans > sch_times[SINK] && sch_times[SINK] - Time.currentTicks() > TIMECONST)	//If there isn't a resyncing too close in time
				{
					listentime = sch_times[SINK] - Time.currentTicks() - TIMECONST;					//Listen up until resyncing		
				}
				else if(sch_times[SINK]>= t_trans && t_trans - Time.currentTicks() > TIMECONST)		//If there isn't transmission too close in time
				{	
					listentime = t_trans - Time.currentTicks() - TIMECONST;							//Listen up until transmission							
				} 
			}
			else if (t_trans  -  sorted[0] > TIMECONST && sch_times[0] -  sorted[0] > TIMECONST)  	//If no transmission/resync is due shortly					
			{
				alarmtime 	= sorted[0];
				listentime 	= t_trans - sorted[0]; 								
			}
			if(listentime > 0 )																		//If listen time is established
			{				
				RelayNode.ChangeState(RECV_STATE);													//Change the relay node state								
				RelayNode.ChangeListenTime(listentime);												//Pass the time it should listen for
				RelayNode.ChangeListenChannel(tindex);												//Pass the channel to be listened for
				
				if(alarmtime == 0) RelayNode.GetTimer(TRECV).setAlarmBySpan(0);						//Invoke now if there is no alarm time (meaning no expected arrival time
				else RelayNode.GetTimer(TRECV).setAlarmTime(alarmtime - TIMECONST);					//If deadline -> start listening TIMECONST time before
				
				tlisten.setAlarmBySpan(listentime);													//Come back after listening to schedule something else
				scheduled = true;								
	
				//LogMessage(csr.s2b(" Scheduled Listen for channel "),(byte)tindex);
			}
		}
		if(!scheduled)
		{
			tlisten.setAlarmBySpan(20*TIMECONST);													//If nothing is scheduled come back and try scheduling after minimum time distance	
			LogMessage(csr.s2b(" Listen Failed - Schedule again "),(byte)0);											
		}
	}
	
	/**********************************
	 * Callback for the tlisten timer *
	 *********************************/
	private static void ListenAgain(byte param, long time)
    {
    	ScheduleListen();
    }
	
	/*************************************************** SETTERS ************************************************************************/
	// Update transmission period for SINK
 	public static void UpdatePeriod(int index,long period)
	{
		if(RelayNode.GetState() == CALC_STATE) if(period >= 0) periods[index] = period;
	}
	
	//Update scheduling(listening) times
	public static void UpdateSchTime(int index,long time)
	{
		if(RelayNode.GetState() == CALC_STATE || RelayNode.GetState() == SYNC_STATE) sch_times[index] = time;
		else sch_times[index] = Time.currentTicks() + periods[index];
	}
	
	//Update the believed number of beacon frames
	public static void UpdateNbeacons(int truebns)
	{
		if(RelayNode.GetState() == CALC_STATE || RelayNode.GetState() == SYNC_STATE) nbeacons 	= truebns;
	}

	//Update the believed number of beacon frames
	public static void UpdateTransTime(long time)
	{
		if(RelayNode.GetState() == CALC_STATE || RelayNode.GetState() == SYNC_STATE) if(time > 0) t_trans = time;
	}
	
	
	/************************************************* HOUSEKEEPING ***********************************************************/
	// Housekeeping -> keeps the sch_time array always up to date. Should have been one method but mote runner complains
    private static void UpdateCHS1(byte index,long time)
    {
    	sch_times[CHS1] += periods[CHS1];						//update next arrival time
    	tsrs1.setAlarmTime(sch_times[CHS1]);					//call itself at that time to update it again
    }
    private static void UpdateCHS2(byte index,long time)
    {
    	sch_times[CHS2] += periods[CHS2];
    	tsrs2.setAlarmTime(sch_times[CHS2]);
    }
    private static void UpdateCHS3(byte index,long time)
    {
    	sch_times[CHS3] += periods[CHS3];
    	tsrs3.setAlarmTime(sch_times[CHS3]);
    }
	
    //Kistarts a timer , and cancel the pervious one. Purpose -> syncing with sources on receiving of a packet.
    public static void KickStartTimer(int index)
    {
    	if(tsrs1.getAlarmTime() == 0 && index == 1)				//if there is no alarm , set one
    	{
    		tsrs1.setAlarmBySpan(periods[index]);
    	}
    	else if(tsrs2.getAlarmTime() == 0 && index == 2)
    	{
    		tsrs2.setAlarmBySpan(periods[index]);
    	}
    	else if(tsrs3.getAlarmTime() == 0 && index == 3)
    	{
    		tsrs3.setAlarmBySpan(periods[index]);
    	}
    }
    
    //Bubble Sort. Not sure why I have it but I am too scared to change anything.
    private static long[] SortArray(long[] deadlines)
    {
    	long[] sorted	= new long[deadlines.length - 1]; 
    	boolean flag = true; 
    	long temp;
    	int j;
    	
		for(int i=1;i<deadlines.length; i++) sorted[i-1] = deadlines[i];
 
		while(flag)
	    {
           flag = false;   
           for(j=0; j < sorted.length -1; j++)
           {
				if(sorted[j] > sorted[j+1])  
				{
					temp = sorted[j];                
	   				sorted[j] = sorted[j+1];
	   				sorted[j+1] = temp;
	   				
	   				flag = true;             
				} 
            } 
	    } 
		return sorted;
    }
    
    //Returns the firt index in the array corresponding to the supplied value
    private static int GetIndexFromValue(long[] deadlines,long value)
    {
    	for(int j=1;j<deadlines.length; j++)  if(deadlines[j] == value) return j;
    	return -1;
    }
    
    public static void LogMessage(byte[] line1, long time)
    {
    	Logger.appendString(line1);
	 	Logger.appendLong(time);
    	Logger.flush(Mote.WARN);
    }
}
