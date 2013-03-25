/*
 * MechRobot.c
 * Small Animal Rescue Robot v1.0
 *
 * Licensed under the MIT License
 * Copyright (C) 2012 @alan_si
 * http://mit-license.org/
 *
 * Designed and built with love @alan_si @cdeloyer @gkiller8
 */

// Include PIC24 libraries
#include "p24HJ64GP502.h"
#include "timer.h"
#include "uart.h"
#include "adc.h"
#include "outcompare.h"
#include "libpic30.h"

// Define shorthand macros
#define	FCY	3685000
#define BUFF_SIZE 32
#define SERVO_MAX 3793
#define SERVO_MIN 2972

#define true 1
#define false 0

// Configuration Bits
_FBS	(BWRP_WRPROTECT_ON & BSS_HIGH_LARGE_BOOT_CODE & RBS_LARGE_BOOT_RAM);
_FSS	(RSS_LARGE_SEC_RAM & SSS_HIGH_LARGE_SEC_CODE & SWRP_WRPROTECT_ON);
_FGS	(GSS_OFF & GCP_OFF & GWRP_OFF);
_FOSCSEL(FNOSC_FRC & IESO_OFF);
_FOSC	(POSCMD_NONE & OSCIOFNC_ON & IOL1WAY_ON & FCKSM_CSDCMD);
_FWDT	(FWDTEN_OFF & WINDIS_OFF & WDTPRE_PR128 & WDTPOST_PS32768);
_FPOR	(FPWRT_PWR128 & ALTI2C_OFF);
_FICD	(ICS_PGD1 & JTAGEN_OFF);

//Function prototypes
void InitIO (void);
void InitTimer (void);
void InitTimer2(void);
void InitPWM(void);
void InitUART(void);

void ADC (void);
void Claw(void);
void CondPWM(void);
void Drive(void);
void ProcessData(void);
void SendData(void);

int ValidateServoLimits(int time);

// Global variables for main drivesystem control
unsigned int stop = false;
unsigned int center_forward = false;
unsigned int center_backward = false;
unsigned int pivot_left = false;
unsigned int pivot_right = false;
unsigned int left_forward = false;
unsigned int left_backward = false;
unsigned int right_forward = false;
unsigned int right_backward = false;

unsigned int PWM_manual_override = false;
unsigned int PWM1 = 65535;
unsigned int PWM2 = 65535;

// Global variables to controll stepper mootor pulley system
unsigned int pause = false;
unsigned int step_ccw = false;
unsigned int step_cw = false;
unsigned int step_rate_interrupt_interval = 30000;

// Global variables to control the claw mechanism
unsigned int XXXTime_manual_override = false;
unsigned int claw_open = false;
unsigned int claw_close = false;
unsigned int limit_switch = false;
unsigned int timer_state = false;
unsigned int reset = false;
unsigned int timer_value = 0;
unsigned int ONTime = 1200;
unsigned int OFFTime = 3073;
unsigned int stepstatus_up = 0;
unsigned int stepstatus_down = 0;

// Global variables for UART bluetooth control
int frame_synchronization_error = 0;
int error_count = 0;
int uart_index = 0;
unsigned char SendDataArray[BUFF_SIZE];
unsigned char ReceiveDataArray[BUFF_SIZE];
unsigned char ReceivedChar = 0;
unsigned char ProcessedChar = 0;

int main (void)
{
	InitIO();
	InitTimer();
	InitTimer2();
	InitUART();
	InitPWM();

	// Initialize the send buffer to zero and set first element as 's' for frame error checking
	SendDataArray[0] = 's';
	int i;
	for (i = 1; i < BUFF_SIZE; i++)
	{
		SendDataArray[i] = 0;
	}

	while (1) {

	if (center_forward == true) {
		// Set motor 1 CCW and motor 2 CCW
		LATBbits.LATB13 = 0;
		LATBbits.LATB7 = 1;
		LATBbits.LATB6 = 1;
		PWM1 = 5000;
		PWM2 = 5000;
	} else if (center_backward == true) {
		// Set motor 1 CW and motor 2 CW
		LATBbits.LATB13 = 1;
		LATBbits.LATB7 = 0;
		LATBbits.LATB6 = 1;
		PWM1 = 5000;
		PWM2 = 5000;
	} else if (pivot_left == true) {
		// Set motor 1 CW and motor 2 CCW
		LATBbits.LATB13 = 1;
		LATBbits.LATB7 = 1;
		LATBbits.LATB6 = 1;
		PWM1 = 25000;
		PWM2 = 25000;
		} 
	else if (pivot_right == true) {
		// Set motor 1 CCW and motor 2 CW
		LATBbits.LATB13 = 0;
		LATBbits.LATB7 = 0;
		LATBbits.LATB6 = 1;
		PWM1 = 25000;
		PWM2 = 25000;
	}
	else if (stop == true)
	{
		LATBbits.LATB6 = 0;
		PWM1 = 65535;
		PWM2 = 65535;
	}
	else if (pause == true)
	{
		step_cw = false;
		step_ccw = false;
	}
	//ADC();						// Call ADC which configures and reads analog input 4 (AN4)
	ProcessData();
	//SendData();
	Drive();
	Claw();
	}
}

void InitIO(void) {
	// Set inputs (variable resistors)
	TRISAbits.TRISA0 = 1;
	TRISAbits.TRISA1 = 1;

	// Set outputs
	TRISBbits.TRISB6 = 0;			// Set RB6 as output (Disable Signal for Motors 1 and 2)
	TRISBbits.TRISB7 = 0;			// Set RB7 as output (Direction Signal for Motor 2)
	TRISBbits.TRISB12 = 0;			// Set RB12 as output (PWM Signal for Motor 2)
	TRISBbits.TRISB13 = 0;			// Set RB13 as output (Direction Signal for Motor 1)
	TRISBbits.TRISB14 = 0;			// Set RB14 as output (PWM Signal for Motor 1)
	
	TRISAbits.TRISA3 = 0;
	TRISAbits.TRISA4 = 0;
	// Set outputs for stepper motor
	TRISBbits.TRISB4 = 0;			// RB4 controls stepper on/off

	TRISBbits.TRISB5 = 0;			// Set RB5 as Output to claw
	TRISBbits.TRISB9 = 0;

	// Configure the appropriate Peripheral Pin Select bits to map OC1 and OC2 output pins to RB14 and RB12
	RPOR7bits.RP14R = 18;			// Set RP14 as OC1 output on pin RB14
	RPOR6bits.RP12R = 19;			// Set RP12 as OC2 output on pin RB12



	// Pins set to the UART Header on MicroStick
	RPINR18bits.U1RXR = 10;			// Set the RP10 pin to UART1 RX pin
	RPOR5bits.RP11R = 3;			// Set the RP11 pin to UART1 TX pin
	RPOR7bits.RP14R = 18;			// Set RP14(RB14) as OC1 output which is used to control motor speed

}

void InitTimer(void)
{
	// Prescaler = 1:1, Period = 0x0FFF, set interrupt priority to 6 and turn on interrupt
	OpenTimer1 (T1_ON & T1_PS_1_1 & T1_SYNC_EXT_OFF & T1_SOURCE_INT & T1_GATE_OFF & T1_IDLE_STOP, 0x0FFF);
	ConfigIntTimer1 (T1_INT_PRIOR_1 & T1_INT_ON);
}

void InitTimer2(void)
{
	OpenTimer3 (T3_ON & T3_PS_1_1 & T3_SOURCE_INT & T3_GATE_OFF & T3_IDLE_STOP, 0x0FFF);
	ConfigIntTimer3 (T3_INT_PRIOR_1 & T3_INT_ON);
}

void InitPWM(void) {
	// Disable Interupts for Timer2, OC1, and OC2
	DisableIntT2;
	DisableIntOC1;
	DisableIntOC2;

	//Configure PWM mode for OC1 and OC2 using Timer2 as the clock source
	OpenOC1(OC_IDLE_CON & OC_TIMER2_SRC & OC_PWM_FAULT_PIN_DISABLE, 1, 1);
	OpenOC2(OC_IDLE_CON & OC_TIMER2_SRC & OC_PWM_FAULT_PIN_DISABLE, 1, 1);

	// Prescaler = 1:1, Period = 0x0FFF
	OpenTimer2(T2_ON & T2_IDLE_CON & T2_GATE_OFF & T2_PS_1_1 & T2_32BIT_MODE_OFF & T2_SOURCE_INT, 0xFFFF);
}

void InitUART(void) {
	IEC0bits.U1TXIE = 0; 		// Disable UART TX interrupt
    IFS0bits.U1RXIF = 0;		// Clear the Recieve Interrupt Flag

	U1MODEbits.STSEL = 0; 		// 1 Stop it
	U1MODEbits.PDSEL = 0;	 	// 8-bit data, no parity
	U1MODEbits.BRGH = 0;		// 16x baud clock, Standard mode
	U1MODEbits.URXINV = 0;		// Idle State 1 for RX pin
	U1MODEbits.ABAUD = 0;		// Auto-Baud Disabled
	U1MODEbits.RTSMD = 1;		// Simplex Mode, no flow control
	U1MODEbits.UARTEN = 1; 		// Enable UART1
	U1STAbits.UTXISEL0 = 0; 	// Interrupt after one TX character is transmitted
	U1STAbits.UTXISEL1 = 0; 	// Interrupt after one TX character is transmitted
	U1STAbits.UTXEN = 1; 		// Enable UART1 to control TX pin
	U1BRG = 22; 			 		// BAUD Rate Setting for 115200

	IEC0bits.U1RXIE = 1;		// Enable UART RX interrupt
}

void Claw(void) {
	if (claw_close == true)
	{
		ONTime = 3370;		//When the CloseClaw signal is sent the Claw will close
	}
	else if(claw_open == true)
	{
		ONTime = 1200;		//When the open claw is sent the claw will open

	}

}

void Drive(void) {


	// Set duty cycle of PWM
	SetDCOC1PWM(PWM1);
	SetDCOC2PWM(PWM2);
}

void ProcessData(void)
{
	center_forward = ReceiveDataArray[2];
	center_backward = ReceiveDataArray[4];
	pivot_left = ReceiveDataArray[6];
	pivot_right = ReceiveDataArray[8];
	stop = ReceiveDataArray[10];

	step_ccw = ReceiveDataArray[12];
	step_cw = ReceiveDataArray[14];
	claw_open = ReceiveDataArray[16];
	claw_close = ReceiveDataArray[18];
	//ONTime = ValidateServoLimits(ONTime);
	pause = ReceiveDataArray[20];
	reset = ReceiveDataArray[22];
}

//int ValidateServoLimits(int time) {
	// Check and correct the position of the claw mechanism
//	if (time > SERVO_MAX) {
//		return SERVO_MAX;
//	} else if (time < SERVO_MIN) {
//		return SERVO_MIN;
//	} else {
//		return time;
//	}
//}

/*
void SendData(void) {
	// Iterate through the array and send one byte of the data array to UART Tx Pin at a time
	int i;
	for (i = 0; i < BUFF_SIZE; i++) {
		WriteUART1(SendDataArray[i]);
		while(BusyUART1());
	}
}
*/
void __attribute__((interrupt, auto_psv)) _T1Interrupt(void)
{
	DisableIntT1;

 	// Generate the square wave signal (ONTime and OFFTime)
	// For OFFTime change prescaler to 1:8 and ONTime uses prescaler 1:64
	// Set the timer state and value accordingly
	if (timer_state == false) {
		LATBbits.LATB9 = 1;
		T1CONbits.TCKPS = 1;
		timer_value = ONTime;
		timer_state = true;
	} else if (timer_state == true) {
		LATBbits.LATB9 = 0;
		timer_value = OFFTime;
		T1CONbits.TCKPS = 2;
		timer_state = false;
	}

	// Set Timer1 value and reset T1 for next interrupt
	WriteTimer1(timer_value);
	IFS0bits.T1IF = 0;
	EnableIntT1;
}

void __attribute__((interrupt, auto_psv)) _T3Interrupt(void) {
	DisableIntT3;

	// Set direction of the the Stepper Motor and performs one step
	if (step_cw == true)
	{
		LATBbits.LATB9 = 0;
		LATBbits.LATB4 = 1 ^ PORTBbits.RB4;
	} 
	else if (step_ccw == true)
	{
		LATBbits.LATB9 = 1;
		LATBbits.LATB4 = 1 ^ PORTBbits.RB4;
	}

	// Set Timer2 value and reset T2 for next interrupt
	WriteTimer3(step_rate_interrupt_interval);
	IFS0bits.T3IF = 0;
	EnableIntT3;
}

void __attribute__ ((interrupt, no_auto_psv)) _U1RXInterrupt(void)
{
	// Pause interrupt while processing received data

	DisableIntU1RX;				// Disable the UART1 receive interrupt
	IFS0bits.U1RXIF = 0;		// Reset the UART1 receive interrupt flag

	ReceivedChar = U1RXREG;
    ProcessedChar = (ReceivedChar >> 1);
	if (ProcessedChar == 'y') {
		frame_synchronization_error = 0;
		uart_index = 0;	
	}

	// Check if frame error (if index is 0, we should have received an 's')
	if ((uart_index == 0) && (ProcessedChar != 'y'))
	{
		error_count++;
		frame_synchronization_error = 1;
	}

	// If no frame error
	if (!frame_synchronization_error)
	{
		if (uart_index == BUFF_SIZE) {
			uart_index = 0;
		} else {
			ReceiveDataArray[uart_index] = ReceivedChar;
			uart_index++;
		}	
	}
	else
	{
		if (ProcessedChar == 'y')
		{
			frame_synchronization_error = 0;
			uart_index = 1;
		}
	}
	EnableIntU1RX;				// Enable the UART1 receive interrupt

}