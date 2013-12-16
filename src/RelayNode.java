package embs;

import com.ibm.saguaro.system.*;
import com.ibm.saguaro.logger.*;

/* @author : Y6329127 */
public class RelayNode {

	private static Timer  	tsync;	// timer responsible for syncing
 	private static Timer  	tsend;	// timer responsinble for transmission 
 	private static Timer  	trecv;	// timer responsinble for receiving from sources 

 	private static byte[] 	xmit12;
  	private static int 		breceived 	= 0; 			// beacon frames received
  	private static long 	freceived 	= 0;			// time first beacon received
  	private static int		nbeacons	= 0;			// number of syncing beacons
  	private static long		period		= 0;			// period between 2 beacon frames
  	private static boolean	stopreceive	= false;		// radio receiving has been stopped
  	private static boolean	receivedfr	= false;		// check if received anything at the end of listen period
  	private static int		currstate	= 1;			// current state of the relay mode
  	private static long		listentime	= 0;			// period for listening
  	private static int		nextchannel	= 0;			// next channel to be listened to
  	private static int		framesgot	= 0;			// counter for the frames received
  	
  	//States
    private static final int CALC_STATE	= 1; 			//Calculating period
    private static final int SYNC_STATE	= 2; 			//Syncing
    private static final int RECV_STATE	= 3; 			//Receiving from sources
    
    //Relay Constants
    private static final long TTMAX 		= 3000; 	// maximum time for 2 frames
    private static final long TMAX 			= 1500; 	// maximum time for 2 frames
    private static final long TMIN 			= 500; 		// minimum t
    private static final int  FRMS_RESYNC 	= 13; 		// frames between resyncs
    private static final int  PTCLLEN	 	= 11;		// Lenght of the protocol addressing

	private static final byte panid 		= 0x11;
    private static final byte address 		= 0x10; 
       
    // Buffer Management
	private static byte[] msgs 	= new byte[50];			//buffer for the messages
	private static int nextsend = 0;					//pointer to the start of the next payload to be sent
	private static int nextfree = 0;					//pointer to the start of the next free location.
    
    // Channels
 	private static final byte[] CHANNELS = new byte[] {0,1,2,3}; 		
	private static final byte[] PANIDS 	 = new byte[] {0x11,0x12,0x13,0x14};
	private static final byte[] ADDRSS 	 = new byte[] {0x11,0x12,0x13,0x14};  // In case addresses are different to panids   
       
    //Indeces
    private static final int SINK 	= 0;
	private static final int CHS1	= 1;
	private static final int CHS2	= 2;
	private static final int CHS3	= 3;
       
    private static Radio radio 	= new Radio();
    private static long wait 	= 0;
    
    //TEMPS
    private static int		tempcl		= 0;
    private static int		counter		= 1;			// counter for the frames

    //mote-create -n a; mote-create -n b; mote-create -n c; mote-create -n sink; mote-create -n relay; moma-load logger; 
    //a0 moma-load SO1; a1 moma-load SO2; a2 moma-load SO3; a3 moma-load Sink; a4 moma-load Relay;
    
    static {
        
        wait = Time.toTickSpan(Time.MILLISECS, TTMAX);
        
        radio.open(Radio.DID, null, 0, 0);				// Open the default radio
        radio.setChannel(CHANNELS[SINK]);				// Set channel 
        radio.setPanId(PANIDS[SINK], true);				// Set the PAN ID and the short address
        radio.setShortAddr(address);
        
        // Register delegate for received frames
        radio.setRxHandler(new DevCallback(null){
                public int invoke (int flags, byte[] data, int len, int info, long time) {
                    return  RelayNode.onReceive(flags, data, len, info, time);
                }
            });
        
        radio.startRx(Device.ASAP, 0, Time.currentTicks() + 0x7FFFFFFF);
        
        //Timer - checks if 2 beacon frames have been received after listening for tmax time
		tsync = new Timer();
        tsync.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
			        RelayNode.SyncCheck(param, time);
                }
            });  
        
	    // Setup a periodic timer callback for beacon transmissions
        tsend = new Timer();
        tsend.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    RelayNode.Send(param, time);
                }
            });
        
        // Setup a periodic timer for receiving packets
        trecv = new Timer();
        trecv.setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    RelayNode.Listen(param, time);
                }
            });  
    }
      
     /******************************************************************************************************************************************************
 	* Summary: Stores payload alongside its lenght in the buffer. Can cope with variable payload sizes (since Leandro doesn't want us to assume anything)  *
 	*------------------------------------------------------------------------------------------------------------------------------------------------------*
 	* This is a cycling buffer. If when storing a payload the next-to-send frame is overwritten,nextsend counter is moved to point to the next payload.    *
 	*																																					   *
 	* The payloads are stored in the following manner -> size of the payload alway followed by the corresponding payload.								   *
 	* Example: Buffer = [size1][data1]/[size2][data]																									   *
 	*																																					   *
 	*@param 																																			   *	
 	*	size - > size paylod to store																													   *
 	*	data - > array of payload data																													   *
 	*	len	 - > length of the whole packet																												   *
 	*																																					   *
 	********************************************************************************************************************************************************/
	public static void StoreInBuffer(int size,byte[]data,int len)
	{
		msgs[nextfree % msgs.length] = (byte)size;												//Store size
		nextfree ++;
		
		for(int i = PTCLLEN; i < len; i++)														//Store payload
		{
			if(nextfree % msgs.length == nextsend  % msgs.length && framesgot > 0)
			{
				nextsend = nextsend + msgs[nextsend % msgs.length] + 1;							//Pointer to next send is moved if overwritten
			}
			msgs[nextfree % msgs.length] = data[i];
			nextfree ++;	
		}
	} 
     
   /*********************************************************************************************************************************************************
 	* Summary: Invoked upon receiving a frame. Responds according the current state of the relay node:														*							
    * ------------------------------------------------------------------------------------------------------------------------------------------------------*				
    * CALC => establish period and inform scheduler	about:																									*	
    * 			- period(t)																																	*
    * 			- number of beacons(n)																														*
    * 			- next tranmission time(t_trans)																											*
    * 		  																																				*
   	*			*In case the first beacon is with payload 1 -> listen for packets for 7*Tmin and switch back to beacon frames 								*
   	*			for 7*(Tmax - Tmin) + Tmin																													*	
    *-------------------------------------------------------------------------------------------------------------------------------------------------------*						
    * SYNC => update number of beacons and next transmission time and next resync time 																		*
    *-------------------------------------------------------------------------------------------------------------------------------------------------------*				
    * RECV => store payload in appropriate array and update	arrival time																					*
    *  																																						*
 	********************************************************************************************************************************************************/
    private static int onReceive (int flags, byte[] data, int len, int info, long time) 
    {
	    if(data != null) 
	    {	
	    	switch(currstate)
    		{
    			case CALC_STATE:
    			
	    			if(breceived == 0)																			//If no beacon frames have been received, ever
			    	{
			    	 	freceived 	= time;																		//Store time of first beacon frame
			    	 	nbeacons 	= data[11];
			        	breceived ++;
			        	
			        	if(data[11] == 1) HandleGettingLastBeacon();
			        	
			        	LogMessage(csr.s2b("Beacon Received at time "),csr.s2b("Payload is "),time,data[11]);
			    	}
			    	else if(breceived == 1)
			    	{ 
			    		long t_trans = 0;
			    		long resync	 = 0;
			    		
			    		StopListening();																		//Stop the radio and save power
			    		
			    		period 	= (time - freceived)/(nbeacons - data[11]);										//in case the realy doesn't get 2 consecutive beacon frames
			    	 	t_trans = Time.currentTicks() + data[11] * period + period/2;							//next transmission time - > aims for transmitting in the middle
			    	 	resync 	= t_trans + 6 * period - wait/4;												//est time for the next first beacon frame
							
						tsync.setAlarmTime(resync);																								
																												/*Update the scheduler with: */
			        	Scheduler.UpdatePeriod(SINK,period);													// - sink's transmission period
			        	Scheduler.UpdateSchTime(SINK,resync);													// - next resyncing time
			        	Scheduler.UpdateNbeacons(nbeacons);														// - believed number of beacons
			        	Scheduler.UpdateTransTime(t_trans);														// - next transmission time
			        	
			        	Scheduler.ScheduleListen();																//Invoke the scheduler to schedule next listens and tranmissions
			        	Scheduler.ScheduleTransmission();
						
			        	breceived++;																			
			        	LogMessage(csr.s2b("Beacon Received at time "),csr.s2b("Payload is "),time,data[11]);
			    	}
			    	break;
			    	
		        case RECV_STATE:
					
		        	int channelindex = 0;
		        	
		        	StopListening();																			//Stop the radio and save power																			
					StoreInBuffer(len - PTCLLEN,data,len);														//Store the payload
					
					if(Util.get16le(data, 9) == ADDRSS[CHS1]) channelindex = CHS1;								//Establish which is the source of the packet
					else if(Util.get16le(data, 9) == ADDRSS[CHS2]) channelindex = CHS2;
					else if(Util.get16le(data, 9) == ADDRSS[CHS3]) channelindex = CHS3;
					
					Scheduler.UpdateSchTime(channelindex,0);													//Sync with that source -> update next expected arrival time	
					Scheduler.KickStartTimer(channelindex); 													//Try to kickstart a timer keeping the next arrival time up to date
					Scheduler.ScheduleListen();																	//Schedule another listen

					framesgot ++;																				//Increment the amount of frames received and not forwarded
					
					LogMessage(csr.s2b("Received message at "),csr.s2b(" Channel is  "),Time.toTickSpan(Time.MILLISECS, 5),(byte)channelindex); 
		        	break;
		        	
		        case SYNC_STATE:
    				
    				StopListening();
    				
    				if(nbeacons < data[11])																		//If number of beacons is greater than believed, notify the scheduler
    				{ 
    					if(breceived == 1) 																		//If period is unestablished (in case the first frame the relay  
    					{																						//got was with payload == 1)
    						period = (time - freceived)/7;														
    						Scheduler.UpdatePeriod(SINK,period);	
    						breceived ++;
    					}
    					nbeacons = data[11];								
    					Scheduler.UpdateNbeacons(nbeacons);												
    				}
    				
    				long next_trans_time = Time.currentTicks() +  data[11] * period + period/2; 				//Calculate the next transmission time and next resync time 
    				long next_resync	 = Time.currentTicks() +  (data[11] + 6) * period + (FRMS_RESYNC -1) *(nbeacons + 6) * period - period/5; 
    				
					tsync.setAlarmTime(next_resync);															//Schedule next resync time
    				tsend.cancelAlarm();																		//If there was a trigger timer for send, cancel it
    																											//because next transmission time will be updated
    				//Notify the scheduler and update important variables
	    			Scheduler.UpdateSchTime(SINK,next_resync);													//Pass next transmission time and resync time
	    			Scheduler.UpdateTransTime(next_trans_time);
	    			Scheduler.ScheduleTransmission();															//And schedule another transmission and listen
	    			Scheduler.ScheduleListen();
	    			
		    		currstate = RECV_STATE;
		    		
		        	LogMessage(csr.s2b("Resyncing beacon received at "),csr.s2b(" Payload is "),time,data[11]);
		        	break;
		    }
		}
	    return 0;
    }

	/********************************************************************************************************************************************
 	* Summary: Transmits to sink and schedules the next transmission 																			*							
 	*********************************************************************************************************************************************/
    private static void Send(byte param, long time) 
    {
    	Scheduler.ScheduleTransmission();																		//Schedule next transmission and listen regardless of whether the relay sent anything
    	Scheduler.ScheduleListen();
    	
    	if(framesgot != 0)																						//if there are frames to be forwarded
		{
			int msgsize = msgs[nextsend % msgs.length];															//get the size of the payload
			xmit12 	= new byte[PTCLLEN + msgsize];																//allocate big enough frame
			PrepareFrame();																						//Load addressing												
			nextsend++;																							
			
			for(int j = 0; j< msgsize; j++)																		//Load the payload into the frame
			{
				xmit12[PTCLLEN + j] = msgs[nextsend % msgs.length];
				nextsend++;
			}
			
			ChangeChannel(SINK);																				//Change channel and transmit
			radio.transmit(Device.ASAP|Radio.TXMODE_POWER_MAX, xmit12, 0, xmit12.length, 0);
			
			LogMessage(csr.s2b("Sending Frame at "),csr.s2b(" number "),time,(byte)counter);
			framesgot --;																						//Decrement counter for non-forwarded frames
			counter++;
		}
		else
		{
			tempcl ++;
			LogMessage(csr.s2b(" Cancell transmission. "),csr.s2b(" Cancelled frames are "),time,(byte)tempcl);
		}
    	
    }
    
    
    /********************************************************************************************************************************************
 	* Summary: Callback for the tsync timer. Tries to establish the next syncing action  according to the state of the relay 					*									
 	********************************************************************************************************************************************/
    private static void SyncCheck(byte param, long time) 
    {	
    	switch(currstate)
    	{
		    case RECV_STATE:
                    
	            stopreceive = false;															
	            currstate = SYNC_STATE;																		    //Change mode to synchronisation
	            
	            ChangeChannel(SINK);
	            radio.startRx(Device.ASAP, 0, Time.currentTicks() + wait);         
	            
	            LogMessage(csr.s2b("Resyncing at  "),csr.s2b(" foor "),time,(byte)wait);
	            break;
    	}
    }
    
   	/********************************************************************************************************************************************
 	* Summary: Callback of the trecv timer. Starts the receiving mode of the radio for listentime preset by the scheduler.						*
 	*********************************************************************************************************************************************/
    private static void Listen(byte param, long time) 
    {
    	ChangeChannel(nextchannel);											
    	radio.startRx(Device.ASAP, 0, Time.currentTicks() + listentime);
    }
    
   	/********************************************************************************************************************************************
 	* Summary: Stops the radio if it is not in stand by or is already stopped.  																*
 	*********************************************************************************************************************************************/
    public static void StopListening()
    {
    	radio.setState(Device.S_OFF);
    }
	
	/********************************************************************************************************************************************
 	* Summary: Changes channel iff not already changed. Takes entity index as parameter.  														*
 	*-------------------------------------------------------------------------------------------------------------------------------------------*
 	* @param 																																	*
 	* ch -> channel index																													    *
 	*********************************************************************************************************************************************/
	public static void ChangeChannel(int ch)
	{
		if(radio.getChannel() != CHANNELS[ch])
		{
			StopListening();
			stopreceive = false;
			
			radio.setChannel(CHANNELS[ch]);
			radio.setPanId(PANIDS[ch],true);
		}
	}
    
    
   	/********************************************************************************************************************************************
 	* Summary: Returns timer entity according to the passed timer index. Invoked by scheduler to set alarms on timers. 							*
 	*-------------------------------------------------------------------------------------------------------------------------------------------*
 	*@param																																		*
 	* timer -> timer index																														*
 	*********************************************************************************************************************************************/
    public static Timer GetTimer(int timer)
    {
    	if(timer == 1) 		return tsend;
    	else if(timer == 2) return tsync;
    	else	 			return trecv;
    }
    
    /********************************************************************************************************************************************
 	* Summary: Returns the current state of the relay. 																							*
 	*********************************************************************************************************************************************/
	public static int GetState()
	{
		return currstate;
	}
	
	/********************************************************************************************************************************************
 	* Summary: Setter for the state. Invoked by the scheduler to change the state of the relay. 												*
 	*-------------------------------------------------------------------------------------------------------------------------------------------*
 	*@param																																		*
 	* newstate -> index of the new state -> indeces to state mapping is specified in the beginning												*
 	*********************************************************************************************************************************************/
	public static void ChangeState(int newstate)
	{
		if(currstate> 0 && currstate < 4) currstate = newstate;
	}
	
	/***********************************************************************************************************************************************
 	* Summary: Setter for variable listen time used by Listen()-the callback for trecv timer. Invoked by the scheduler to preset the listen period.*
 	*----------------------------------------------------------------------------------------------------------------------------------------------*
 	*@param 																																	   *
 	* time -> value for the listen time																											   *
 	************************************************************************************************************************************************/
    public static void ChangeListenTime(long time)
    {
    	if(time > 0) listentime = time;
    }
    
    /***********************************************************************************************************************************************
 	* Summary: Setter for next source channel to be listened to.Used by the scheduler to assign next channel to be serviced.					   *
 	*----------------------------------------------------------------------------------------------------------------------------------------------*
 	*@param 																																	   *
 	* channel -> index of the next channel																										   *
 	************************************************************************************************************************************************/
    public static void ChangeListenChannel(int channel)
    {
    	nextchannel = channel;
    }
    
    /***********************************************************************************************************************************************
 	* Summary: Prepares a frame by loading in the protocol addressings.																			   *
 	************************************************************************************************************************************************/
    public static void PrepareFrame()
    {
    	// Prepare beacon frame with source and destination addressing
        xmit12[0] = Radio.FCF_BEACON;
        xmit12[1] = Radio.FCA_SRC_SADDR|Radio.FCA_DST_SADDR;
        Util.set16le(xmit12, 3, 0x11); 		// destination PAN address 
        Util.set16le(xmit12, 5, 0x11); 		// destination address 
        Util.set16le(xmit12, 7, panid); 		// own PAN address 
        Util.set16le(xmit12, 9, address); 	// own short address 
    }
    
    public static void LogMessage(byte[] line1, byte[] line2, long time, byte data)
    {
    	Logger.appendString(line1);
	 	Logger.appendLong(time);
	 	Logger.appendString(line2);
	 	Logger.appendByte(data);
    	Logger.flush(Mote.WARN);
    }
    
     /***********************************************************************************************************************************************
 	* Summary: Handling method for the case of first beacon's payload == 1				   															*
 	************************************************************************************************************************************************/
    public static void HandleGettingLastBeacon()
    {
    	ChangeChannel(CHS1);																//Stop listening to beacons and start listening for packets
		listentime = Time.currentTicks() + Time.toTickSpan(Time.MILLISECS, 7*TMIN);			//for 7 * min t
		currstate = RECV_STATE;
		
		radio.startRx(Device.ASAP, 0,listentime - 1000);
		tsync.setAlarmTime(listentime - 1000);
    }
    
    /***********************************************************************************************************************************************
 	* Summary: Getter for the non relayed frames in the buffer					   																   *
 	************************************************************************************************************************************************/
    public static int GetNonRelayedFrames()
    {
    	return framesgot;
    }
    
}
