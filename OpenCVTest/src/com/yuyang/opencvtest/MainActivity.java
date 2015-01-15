package com.yuyang.opencvtest;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.app.Activity;
import android.app.AlertDialog;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CvCameraViewListener2{
	
	private JavaCameraView mOpenCvCameraView;	// camera preview view

	private Mat mIntermediateMat = null;
	private Mat mRgba = null;
	
	private boolean bDetecting = false;		// when set as true, the app will start to detect digitals
	private boolean bIsUserView = false;	// when set as false, some intermediate value will be drawn to help debug
	 
	/**
	 * we use a "locating box" to help locate digital area approximately
	 */
	private double iBoxWidthRatio = 0.4;	// the ratio of the box width / the total width of camera preview
	private final double iBoxAspectRatio = 3.0; // the ratio of the box width / the box height
	private int iBoxTopLeftX;	// the x coordinate of the top left point of the box
    private int iBoxTopLeftY;	// the y coordinate of the top left point of the box
    private int iBoxWidth;		// the width of the box 
    private int iBoxHeight;		// the height of the box 
        
    /**
     * the thresholds for tuning the algorithm
     */
    private final int iDigitalDiffThre = 2;	// the threshold for the difference of project value between digital areas and non-digitals areas	
    
    // we use aspect ratio to check if an area is digital area
    // that is, if the ratio is not in the given range, the area 
    // will be ignored
    private final double dDigitalRatioMin = 0.1;
    private final double dDigitalRatioMax = 0.8;

    // the threshold for the variance of the digitals' widths
    // Based on the priori knowledge that digitals are with equal widths,
    // the variance of the widths must be less than the threshold
    private final double dDigitalWidthVarThre = 0.1; 
	
	// digital "1" is kind of special. We will recognize it 
	// according to its bounding rectangle's aspect ratio 
	private final double dAspectRatioOne = 0.2;	
	
	/**
	 * normally the digitals in LCD is a little slant. we should take 
	 * into account this when applying the vertical projection algorithm.
	 * dDigitalSlope is the slope of the digital 
	 */
	private final double dDigitalSlope = 16.0;	
	
	private ArrayList<Integer> recog_results = null;
	
	private Size sSize5;				// will be used for Gaussian blur
	
    private BaseLoaderCallback  mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                     mOpenCvCameraView.enableView();
                    
                }  
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };
    
    private ImageView ivScanLine;
    
    // message ID
    private final int iMsgShowResults = 0x123;
    private final int iMsgScanAnimation = 0x124;
    
    private String sRecogResults = "";
    private Timer tAnimationTimer = null;
    
    private Handler handler = new Handler()
	{
    	
		@Override
		public void handleMessage(Message msg)
		{
			switch(msg.what) {
			case iMsgShowResults:	// show the recognition results
				ivScanLine.setVisibility(View.INVISIBLE);
        		
				TextView results_view = (TextView) findViewById(R.id.tv_recog_results);
		    	results_view.setText(sRecogResults);
		    	
				break;
			case iMsgScanAnimation: // play scan line animation
				TranslateAnimation mAnimation = new TranslateAnimation(
						TranslateAnimation.ABSOLUTE, 0f, TranslateAnimation.ABSOLUTE,
						0f, TranslateAnimation.RELATIVE_TO_PARENT, 0f,
						TranslateAnimation.RELATIVE_TO_PARENT, 0.9f);
				mAnimation.setDuration(1000);
				mAnimation.setRepeatCount(2);
				mAnimation.setRepeatMode(Animation.REVERSE);
				mAnimation.setInterpolator(new LinearInterpolator());
				ivScanLine.startAnimation(mAnimation);
				break;
			}
		}
	};

	@Override
	protected void onResume() {
		super.onResume();
		
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_9, this, mLoaderCallback);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		
		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_main);
		
		mOpenCvCameraView = (JavaCameraView) findViewById(R.id.OpenCV_view);
		mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
		mOpenCvCameraView.setCvCameraViewListener(this);
		
		ivScanLine = (ImageView) findViewById(R.id.scanner_green_line);
		ivScanLine.setVisibility(View.INVISIBLE);
	}
	
	public void recognize(View v) {
		bDetecting = true;	// start to detect and recognize digitals
		
		ivScanLine.setVisibility(View.VISIBLE);
		tAnimationTimer = new Timer();
		tAnimationTimer.schedule(new TimerTask() {

			@Override
			public void run() {
				handler.sendEmptyMessage(iMsgScanAnimation);
			}
			
		}, 0, 2000);	
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.about:
			new AlertDialog.Builder(this)
				.setTitle("About the app")
				.setMessage("OpenCV based OCR Demo. Version 1.0\nAuthor: yuyang.development@gmail.com")
				.setPositiveButton("OK", null)
				.create().show();
			break;
		}
		return true;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public void onCameraViewStarted(int width, int height) {
		mIntermediateMat = new Mat();
		mRgba = new Mat();
		sSize5 = new Size(5, 5);
		
		DisplayMetrics dm = this.getResources().getDisplayMetrics(); 
        
		// work out the size of locating box
        iBoxWidth = (int) (width * iBoxWidthRatio);
        iBoxHeight = (int) (iBoxWidth / iBoxAspectRatio);
        iBoxTopLeftX = (int) ((width - iBoxWidth) / 2.0);
        iBoxTopLeftY = (int) ((height - iBoxHeight) / 2.0);
        
        RelativeLayout cover_center = (RelativeLayout) findViewById(R.id.cover_center);
		LayoutParams lp = cover_center.getLayoutParams();
		lp.width = (int) (dm.widthPixels * iBoxWidthRatio);
		lp.height = (int) (lp.width / iBoxAspectRatio);
		cover_center.setLayoutParams(lp);
	}

	@Override
	public void onCameraViewStopped() {
		releaseMats();
	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
	
		Scalar color_red = new Scalar( 255, 0, 0 );
        Scalar color_green = new Scalar( 0, 255, 0 );
		
		if (!bDetecting)
			return inputFrame.rgba();
		
		// get the gray image
		Imgproc.cvtColor(inputFrame.rgba(), mIntermediateMat, Imgproc.COLOR_RGBA2GRAY);

        // do Gaussian blur to prevent getting lots false hits
    	Imgproc.GaussianBlur(mIntermediateMat, mIntermediateMat, sSize5, 2, 2);

    	// use Canny edge detecting to get the contours in the image
    	final int iCannyLowerThre = 35;		// threshold for Canny detection
        final int iCannyUpperThre = 75;		// threshold for Canny detection
        Imgproc.Canny(mIntermediateMat, mIntermediateMat, iCannyLowerThre, iCannyUpperThre);
        
        Imgproc.cvtColor(mIntermediateMat, mRgba, Imgproc.COLOR_GRAY2BGRA, 4);
 
        Mat ShownImage;
        // draw the slope line
        if (bIsUserView) {
        	ShownImage = inputFrame.rgba();
        } else {
        	ShownImage = mRgba;
        	
        	// draw a slant line to show the slope
            Core.line(ShownImage, new Point(iBoxTopLeftX+iBoxWidth, iBoxTopLeftY), new Point(iBoxTopLeftX+iBoxWidth-iBoxHeight/dDigitalSlope, iBoxTopLeftY+iBoxHeight), color_green);
            // draw the locating box
            Core.rectangle( ShownImage, new Point(iBoxTopLeftX, iBoxTopLeftY), new Point(iBoxTopLeftX+iBoxWidth, iBoxTopLeftY+iBoxHeight), color_red, 2, 8, 0 );
        }
          
        /**
         * scan vertically (with a little slant) to get the vertical projection of the image (in the box)
         * the projection is actually the number of white points in vertical direction
         */
        int digital_start = -1;
        int digital_end = -1;
        ArrayList<Integer> digital_points = new ArrayList<Integer>();
        int vert_sum = 0;
        int pre_vert_sum = iBoxHeight;
        for (int i = iBoxTopLeftX; i < iBoxTopLeftX+iBoxWidth; i++) {
        	vert_sum = 0;
        	for (int j = iBoxTopLeftY; j < iBoxTopLeftY+iBoxHeight; j++) {
        		int next_x = (int) (i - (j - iBoxTopLeftY) / dDigitalSlope);
        		int next_y = j;
        		
        		if (isWhitePoint(mIntermediateMat, next_x, next_y)) {
        			vert_sum++;
        		}
        	}

        	if (!bIsUserView) { // draw the project value
        		Core.line(ShownImage, new Point(i-iBoxHeight/dDigitalSlope, iBoxTopLeftY+iBoxHeight), new Point(i-iBoxHeight/dDigitalSlope, iBoxTopLeftY+iBoxHeight+vert_sum*10), color_red);
        	}
        	
        	
        	if (digital_start < 0) {
        		int diff = vert_sum - pre_vert_sum;
        		if (diff >= iDigitalDiffThre) {
            		digital_start = i-1;
            	}  else {
            		pre_vert_sum = vert_sum;
            	}
        	} else {
        		if (vert_sum <= pre_vert_sum) {
    				digital_end = i;
    				
    				double ratio = (digital_end - digital_start)/((double) iBoxHeight);
    				if (ratio > dDigitalRatioMin/2) {			
    					digital_points.add(digital_start);		// record the left and right positions of the digital
            	        digital_points.add(digital_end);
    				}
        	        
        	        digital_start = -1;
        	        digital_end = -1;
    			}	
        	}
        }
        	
        /**
         * after finding the positions of the digitals, we will traverse 
         * every digital from three different directions and then recognize
         * them based the traversal results.
         * see following example of digital "3". When we traverse it from
         * line a, b and c, the number of passed segments should be 3, 1 and 1 respectively.
         * Thus the recognition code of "3" is 311. That is, for an unknown digital, 
         * if its code is "311", it should be digital "3".
         *     
         *                 a     
         *                /
         *           ####/#####
         *              /    #
         *    b -----------------
         *            /    #
         *       ####/##### 
         *          /    #
         *   c -----------------
         *        /    #
         *    ###/##### 
         *      / 
         */
        
        // the vertical position of the line of the digitals
        int digitals_line_start = iBoxTopLeftY ;     
        int line_height = iBoxHeight;
        
        //the thresholds for the three traversal directions
        final double vert_mid_thre = 0.5;		// direction a 
        final double hori_upp_thre = 0.35;    	// direction b
        final double hori_low_thre = 0.7;     	// direction c
        
        final double vert_upp_seg_thre = 0.25;	// threshold for check if a segment is located upper when traversing from direction a
        final double hori_left_seg_thre = 0.5;  // threshold for check if a segment is located left when traversing from direction b and c
        
        if (recog_results == null) {
        	recog_results = new ArrayList<Integer>(); // array to record the recognition results
        }
        
        // draw bounding rectangles
        for (int i = 0; i < digital_points.size(); i += 2) { 
        	Core.rectangle(ShownImage, new Point(digital_points.get(i), iBoxTopLeftY), new Point(digital_points.get(i+1), iBoxTopLeftY+iBoxHeight), color_green, 2, 8, 0 );
        }
        
        ArrayList<Integer> digitals_widths = new ArrayList<Integer>(); // widths of the digitals
        int widths_sum = 0;
        if (digital_points.size() > 0) {
        	recog_results.clear();
        	
        	int recog_code = 0; 	// recognition code
        	for (int i = 0; i < digital_points.size(); i += 2) {
        		recog_code = 0;
	
        		int width = digital_points.get(i+1) - digital_points.get(i); // the width of a "digital area" (there might be a digital in it)
        		
        		/**
        		 * sometimes there might be some line above or below digitals
        		 * these lines should be removed to avoid wrong recognition
        		 * Thus we use horizontal project to specify the digital height
        		 * The basic idea is for a real digital area, its horizontal project
        		 * must be more than 0 
        		 */
        		int digital_hori_start = digitals_line_start;
        		int hori_sum;
        		int start_x = digital_points.get(i);
        		int digital_hori_prj_cnt = 0;
        		int tmp;
        		for (tmp = digital_hori_start; tmp < digitals_line_start + line_height/2; tmp++) {
        			hori_sum = 0;
        			int next_x = (int) (start_x - (tmp - digitals_line_start) / dDigitalSlope);
        			for (int k = 0; k < width; k++) {
        				if (isWhitePoint(mIntermediateMat, next_x+k, tmp)) {
        					hori_sum++;
        				}
        			}
        			
        			if (hori_sum > 0) {
        				digital_hori_prj_cnt++;
        				if (digital_hori_prj_cnt == 5) {
        					digital_hori_start = tmp - 6;
        					break;
        				}
        			} else {
        				digital_hori_prj_cnt = 0;
        			}
        		}
        		
        		if (tmp >= digitals_line_start + line_height/2) {
        			continue; // not a digital
        		}
        		
        		int digital_hori_end = digitals_line_start + line_height;
        		digital_hori_prj_cnt = 0;
        		for (tmp = digital_hori_end; tmp > digitals_line_start + line_height/2; tmp--) {
        			hori_sum = 0;
        			int next_x = (int) (start_x - (tmp - digitals_line_start) / dDigitalSlope);
        			for (int k = 0; k < width; k++) {
        				if (isWhitePoint(mIntermediateMat, next_x+k, tmp)) {
        					hori_sum++;
        				}
        			}
        			
        			if (hori_sum > 0) {
        				digital_hori_prj_cnt++;
        				if (digital_hori_prj_cnt == 5) {
        					digital_hori_end = tmp + 6;
        					break;
        				}
        			} else {
        				digital_hori_prj_cnt = 0;
        			}
        		}
        		
        		if (tmp <= digitals_line_start + line_height/2) {
        			continue; // not a digital
        		}
        		
        		int digital_height = digital_hori_end-digital_hori_start+1;
        		
        		if (digital_height < iBoxHeight*0.5) {
        			continue; // the digital should not be too short
        		}
        		
        		// we use aspect ratio to validate the digital area 
        		double digital_ratio = width / ((double) digital_height);
        		
        		if (digital_ratio > dDigitalRatioMax
        				|| digital_ratio < dDigitalRatioMin) {
        			continue;
        		}

        		if (digital_ratio < dAspectRatioOne) { // it should be digital "1" for the low aspect ratio
        			if (i > 0 && digital_points.get(i)-2*width <= digital_points.get(i-1)) {
            			continue;	// if an "1" is too close to the previous digital, it should not be a wrong area
            		}
        			recog_results.add(1);
        			continue;
        		}
        		
        		int vert_line_x = (int) (start_x - (digital_hori_start-digitals_line_start) / dDigitalSlope + (width) * vert_mid_thre);
        		int hori_upp_y = (int) (digital_hori_start + digital_height*hori_upp_thre);
        		int hori_low_y = (int) (digital_hori_start + digital_height*hori_low_thre);
        		
        		// traverse from direction a   		
        		ArrayList<Integer> vertical_results = traverseRect(mIntermediateMat, vert_line_x, digital_hori_start, 0, digital_height);
        		if (vertical_results.size() == 1) { // "4" or "7"
        			if ((vertical_results.get(0) / ((double)digital_height)) < vert_upp_seg_thre ) {
        				recog_results.add(7);
        				digitals_widths.add(width);
        				widths_sum += width;
        			} else {
        				recog_results.add(4);
        				digitals_widths.add(width);
        				widths_sum += width;
        			}
        			continue;
        		}
        		
        		if (vertical_results.size() == 2) { // normally, only "0"'s vertical code is 2
        			if ((vertical_results.get(1) - vertical_results.get(0))/((double)digital_height) < 0.6) {
        				recog_results.add(4);	// sometimes we got vertical code 2 for "4"
        				digitals_widths.add(width);
        				widths_sum += width;
        				continue;
        			}		
        		}

        		int hori_upp_x = (int) (start_x-(hori_upp_y - digitals_line_start)/dDigitalSlope);
        		int hori_low_x = (int) (start_x-(hori_low_y - digitals_line_start)/dDigitalSlope);
        		
        		// traverse from direction b
        		ArrayList<Integer> horizontal_results_upp = traverseRect(mIntermediateMat, hori_upp_x, hori_upp_y, 1, width);
        		
        		// traverse from direction c
        		ArrayList<Integer> horizontal_results_low = traverseRect(mIntermediateMat, hori_low_x, hori_low_y, 1, width);
        		
        		// calculate the recognition code
        		recog_code = vertical_results.size() * 100 + horizontal_results_upp.size() * 10 + horizontal_results_low.size();
        		switch (recog_code) {
        		case 322:
        			recog_results.add(8);
        			digitals_widths.add(width);
        			widths_sum += width;
        			break;
        		case 321:
        			recog_results.add(9);
        			digitals_widths.add(width);
        			widths_sum += width;
        			break;
        		case 312:
        			recog_results.add(6);
        			digitals_widths.add(width);
        			widths_sum += width;
        			break;
        		case 311:
        			if ((horizontal_results_upp.get(0) / ((double)width)) < hori_left_seg_thre) {
        				recog_results.add(5);
        				digitals_widths.add(width);
        				widths_sum += width;
        			} else if ((horizontal_results_low.get(0) / ((double)width)) < hori_left_seg_thre) {
        				recog_results.add(2);
        				digitals_widths.add(width);
        				widths_sum += width;
        			} else {
        				recog_results.add(3);
        				digitals_widths.add(width);
        				widths_sum += width;
        			}
        			break;
        		case 222:
        			recog_results.add(0);
        			digitals_widths.add(width);
        			widths_sum += width;
        			break;
        		case 221:	// sometimes, we got the wrong vertical code 2 for "7". in this case, we have to check the full code
        			recog_results.add(7);	
        			digitals_widths.add(width);
        			widths_sum += width;
        			break;
        		default:
        			recog_results.add(-1);	// wrong recognition result :(	
        			break;
        		}	
        	}
        	
        	/**
        	 * the digitals should have equivalent widths. 
        	 * We use this rule to check if we get the results
        	 */
        	if (isValidDigtals(digitals_widths, widths_sum)) {
        		bDetecting = false;
        		tAnimationTimer.cancel();
        		
        	}
        	
        	// print the final results on the screen
            sRecogResults = "";
        	for (int i = 0; i < recog_results.size(); i++) {
        		int digital = recog_results.get(i);
        		if (digital >= 0) {
        			sRecogResults += String.format("%d, ", digital);
        		} else {
        			sRecogResults += "NA, ";
        		}
        	}

        	handler.sendEmptyMessage(iMsgShowResults);
        }

        if (bIsUserView) {
        	return inputFrame.rgba();
        } else {
        	return mRgba;
        }
	}
	
	private void releaseMats () {

		if (mIntermediateMat != null) {
			mIntermediateMat.release();
		}
		
        if (mRgba != null) {
        	mRgba.release();
        }
    }
	
	/**
	 * Traverse the bounding rectangle from one direction to get segment code and position
	 * @param mat the image matrix
	 * @param start_x x coordinate of the starting point
	 * @param start_y y coordinate of the starting point
	 * @param direct traverse direction (0: vertical, 1: horizontal)
	 * @param distance how far we will traverse
	 * @return a list containing the results. When we detect a segment during traversing, 
	 * we will add the mid point coordinate (in the direction) of the segment to the list.
	 * Thus, the size of the list would be the number of segments we found. We will 
	 * use this info and the coordinates (if needed) to get the recognition code of the digital.
	 */
	private ArrayList<Integer> traverseRect(Mat mat, int start_x, int start_y, int direct, int distance) {
		ArrayList<Integer> results = new ArrayList<Integer>();
		ArrayList<Integer> detected_points = new ArrayList<Integer>();

		// the threshold for the interval between segments 
		double seg_inter_thre;
		if (direct == 1) {
			seg_inter_thre = distance * 0.33;
		} else {
			seg_inter_thre = distance * 0.25;
		}
		
		for (int i = 0; i < distance; i++) {
			int next_x = start_x;
			int next_y = start_y;
			if (direct == 0) { 	// traverse vertically
				next_y += i;
				next_x = (int) (start_x - i / dDigitalSlope);
			} else { 			// traverse horizontally 
				next_x += i;
			}
			
			if (isWhitePoint(mat, next_x, next_y) || i == distance-1) {
				if (detected_points.size() > 0
						&& (i - detected_points.get(detected_points.size()-1) > seg_inter_thre
								|| i == distance-1)) { 
					// should be another segment or we reach the end. So mark the current segment
					int seg_mid = (int) ((detected_points.get(0) + detected_points.get(detected_points.size()-1)) / 2.0);
					results.add(seg_mid);
					
					detected_points.clear();
				}
				
				if (i < distance-1)
					detected_points.add(i);
			}
		}
		
		return results;
	}
	
	/**
	 * check if a point in the image is white 
	 */
	private boolean isWhitePoint(Mat mat, int x, int y) {
		double white_thre = 100.0;
		double[] tmp = mat.get(y, x);
		if (tmp[0] < white_thre) {
			return false;
		} else {
			return true;
		}
	}
	
	/**
	 * check if it is the "final" results are valid (the basic idea is based 
	 * on the priori knowledge that the widths of digitals should be equal)
	 * @param widths the list of area widths
	 * @param sum the sum of area widths
	 * @return true if these widths are equivalent (we use variance as the indicator)
	 */
	private boolean isValidDigtals(ArrayList<Integer> widths, int sum) {
		if (widths.size() == 0) // no digital is detected
			return false;
		
		if (widths.size() == 1) // only one digital is detected
			return true;
		
		// print the final results on the screen
        sRecogResults = "";
         
		double avg = ((double) sum) / widths.size();
		double var_sum = 0.0;
		for (int i = 0; i < widths.size(); i++) {
			
			var_sum += Math.pow(widths.get(i)-avg, 2);
				
	        sRecogResults += String.format("%d, ", widths.get(i));
		}
		
		double variance = var_sum / widths.size();
		
		sRecogResults += String.format("%f, ", variance);
		
    	handler.sendEmptyMessage(iMsgShowResults);
		
		if (Math.sqrt(variance)/avg < dDigitalWidthVarThre) {
			return true;
		} else {
			return false;
		}	
	}
}
