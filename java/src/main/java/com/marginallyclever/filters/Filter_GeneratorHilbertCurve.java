package com.marginallyclever.filters;

import com.marginallyclever.makelangelo.MachineConfiguration;
import com.marginallyclever.makelangelo.MainGUI;
import com.marginallyclever.makelangelo.MultilingualSupport;

import javax.swing.*;
import java.awt.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

public class Filter_GeneratorHilbertCurve extends Filter {
	float turtle_x,turtle_y;
	float turtle_dx,turtle_dy;
	float turtle_step=10.0f;
	float xmax = 7;
	float xmin = -7;
	float ymax = 7;
	float ymin = -7;
	float tool_offset_z = 1.25f;
	float z_down=40;
	float z_up=90;
	int order=4; // controls complexity of curve
	float x,y;

	
	
	public Filter_GeneratorHilbertCurve(MainGUI gui, MachineConfiguration mc,
			MultilingualSupport ms) {
		super(gui, mc, ms);
		// TODO Auto-generated constructor stub
	}

	public String GetName() { return translator.get("HilbertCurveName"); }
	
	/**
	 * Overrides teh basic MoveTo() because optimizing for spirals is different logic than straight lines.
	 */
	protected void MoveTo(OutputStreamWriter out,float x,float y,boolean up) throws IOException {
		tool.WriteMoveTo(out, TX(x), TY(y));
		if(lastup!=up) {
			if(up) liftPen(out);
			else   lowerPen(out);
			lastup=up;
		}
	}
	
	
	public void Generate(final String dest) {
		final JTextField field_size = new JTextField(Integer.toString((int)xmax));
		final JTextField field_order = new JTextField(Integer.toString(order));

	
		JPanel panel = new JPanel(new GridLayout(0,1));
		panel.add(new JLabel(translator.get("HilbertCurveSize")));
		panel.add(field_size);
		panel.add(new JLabel(translator.get("HilbertCurveOrder")));
		panel.add(field_order);
		
	    int result = JOptionPane.showConfirmDialog(null, panel, GetName(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
	    if (result == JOptionPane.OK_OPTION) {
			xmax = Integer.parseInt(field_size.getText())*2;
			ymax= xmax;
			xmin=0;
			ymin=0;
			order = Integer.parseInt(field_order.getText());
			CreateCurveNow(dest);
	    }
	}
	

	private void CreateCurveNow(String dest) {
		try {
			OutputStreamWriter output = new OutputStreamWriter(new FileOutputStream(dest),"UTF-8");
			tool = machine.GetCurrentTool();
			SetupTransform((int)Math.ceil(xmax-xmin),(int)Math.ceil(ymax-ymin));
			output.write(machine.GetConfigLine()+";\n");
			output.write(machine.GetBobbinLine()+";\n");
			tool.WriteChangeTo(output);
						
			turtle_x=0;
			turtle_y=0;
			turtle_dx=0;
			turtle_dy=-1;
			turtle_step = (float)((xmax-xmin) / (Math.pow(2, order)));

			// Draw bounding box
			//SetAbsoluteMode(output);
			liftPen(output);
			MoveTo(output,xmax,ymax,false);
			MoveTo(output,xmax,ymin,false);
			MoveTo(output,xmin,ymin,false);
			MoveTo(output,xmin,ymax,false);
			MoveTo(output,xmax,ymax,false);
			liftPen(output);

			// move to starting position
			x = (xmax-turtle_step/2);
			y = (ymax-turtle_step/2);
			MoveTo(output,x,y,true);
			lowerPen(output);
			// do the curve
			hilbert(output,order);
			liftPen(output);
			
        	output.flush();
	        output.close();
	        
			// open the file automatically to save a click.
			mainGUI.OpenFileOnDemand(dest);
		}
		catch(IOException ex) {}
	}
	
	
    // Hilbert curve
    private void hilbert(OutputStreamWriter output,int n) throws IOException {
        if (n == 0) return;
        turtle_turn(90);
        treblih(output,n-1);
        turtle_goForward(output);
        turtle_turn(-90);
        hilbert(output,n-1);
        turtle_goForward(output);
        hilbert(output,n-1);
        turtle_turn(-90);
        turtle_goForward(output);
        treblih(output,n-1);
        turtle_turn(90);
    }


    // evruc trebliH
    public void treblih(OutputStreamWriter output,int n) throws IOException {
        if (n == 0) return;
        turtle_turn(-90);
        hilbert(output,n-1);
        turtle_goForward(output);
        turtle_turn(90);
        treblih(output,n-1);
        turtle_goForward(output);
        treblih(output,n-1);
        turtle_turn(90);
        turtle_goForward(output);
        hilbert(output,n-1);
        turtle_turn(-90);
    }
    

    public void turtle_turn(float degrees) {
    	double n = degrees * Math.PI / 180.0;
    	double newx =  Math.cos(n) * turtle_dx + Math.sin(n) * turtle_dy;
    	double newy = -Math.sin(n) * turtle_dx + Math.cos(n) * turtle_dy;
    	double len = Math.sqrt(newx*newx + newy*newy);
    	assert(len>0);
    	turtle_dx = (float)(newx/len);
    	turtle_dy = (float)(newy/len);
    }

    
    public void turtle_goForward(OutputStreamWriter output) throws IOException {
    	//turtle_x += turtle_dx * distance;
    	//turtle_y += turtle_dy * distance;
    	//output.write(new String("G0 X"+(turtle_x)+" Y"+(turtle_y)+"\n").getBytes());
    	x+=(turtle_dx*turtle_step);
    	y+=(turtle_dy*turtle_step);
    	MoveTo(output,x,y,false);
    }
}
