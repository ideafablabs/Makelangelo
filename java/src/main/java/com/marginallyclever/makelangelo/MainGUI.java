package com.marginallyclever.makelangelo;
/**@(#)drawbotGUI.java
 *
 * drawbot application with GUI
 *
 * @author Dan Royer (dan@marginallyclever.com)
 * @version 1.00 2012/2/28
 */


// io functions
import com.marginallyclever.filters.*;
import com.marginallyclever.communications.MarginallyCleverConnection;
import com.marginallyclever.communications.MarginallyCleverConnectionManager;
import com.marginallyclever.communications.SerialConnectionManager;
import com.marginallyclever.drawingtools.DrawingTool;

import org.apache.commons.io.IOUtils;
import org.kabeja.dxf.*;
import org.kabeja.parser.ParseException;
import org.kabeja.dxf.helpers.DXFSplineConverter;
import org.kabeja.dxf.helpers.Point;
import org.kabeja.parser.DXFParser;
import org.kabeja.parser.Parser;
import org.kabeja.parser.ParserBuilder;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;


// TODO while not drawing, in-app gcode editing with immediate visual feedback ?
// TODO image processing options - cutoff, exposure, resolution, voronoi stippling, edge tracing ?
// TODO vector output ?
// TODO externalize constants like version and ABOUT_HTML
// TODO externalize constants like version

public class MainGUI
		extends JPanel
		implements ActionListener
{

	// Java required?
	static final long serialVersionUID=1L;

    /**
     * software version
     */
	public static final String version="7.1.0";

	
	// Image processing
		// TODO use a ServiceLoader for plugins
		private List<Filter> image_converters;
		private boolean startConvertingNow;
	
	private Preferences prefs = Preferences.userRoot().node("DrawBot");
	private RecentFiles recentFiles;
	
	private MarginallyCleverConnectionManager connectionManager;  // TODO replace with multi-type connection manager?
	private MarginallyCleverConnection connectionToRobot=null;
		
	// machine settings while running
	private double feed_rate;
	
	// GUI elements
	private static JFrame mainframe;
	private JMenuBar menuBar;
    private JMenuItem buttonExit;
    private JMenuItem buttonAdjustSounds, buttonAdjustGraphics, buttonAdjustLanguage;
    private JMenuItem buttonRescan, buttonDisconnect;
    private JMenuItem buttonZoomIn,buttonZoomOut,buttonZoomToFit;
    private JMenuItem buttonAbout,buttonCheckForUpdate;
    // settings pane
    private JButton buttonAdjustMachineSize, buttonAdjustPulleySize, buttonJogMotors, buttonChangeTool, buttonAdjustTool;
    // prepare pane
    private JButton buttonOpenFile, buttonHilbertCurve, buttonText2GCODE, buttonSaveFile;
    // drive pane
    private MakelangeloDriveControls driveControls;
    
    private JMenuItem [] buttonRecent = new JMenuItem[10];
    private JMenuItem [] buttonPorts;

    private JTabbedPane contextMenu;
    private Splitter split_left_right;
    public boolean dialog_result=false;
    
    // logging
    private JTextPane log;
    private JScrollPane logPane;
    HTMLEditorKit kit;
    HTMLDocument doc;
    PrintWriter logToFile;
    
    // panels
    private DrawPanel previewPane;
	private StatusBar statusBar;
	private JPanel preparePane;
	private JPanel settingsPane;
	

	// reading file
	private boolean isrunning=false;
	private boolean isPaused=true;
	
	private GCodeFile gcode = new GCodeFile();

	private MachineConfiguration machineConfiguration;
	private MultilingualSupport  translator;
	
	
	public MainGUI() {
		StartLog();
		StartTranslator();
		machineConfiguration = new MachineConfiguration(this,translator);
        recentFiles = new RecentFiles();
        connectionManager = new SerialConnectionManager(prefs, this, translator, machineConfiguration);
        LoadImageConverters();
        CreateAndShowGUI();
	}


	public void StartTranslator() {
		translator = new MultilingualSupport();
		if(translator.isThisTheFirstTime()) {
			chooseLanguage();
		}
	}
	
	// display a dialog box of available languages and let the user select their preference.
	public void chooseLanguage() {
		final JDialog driver = new JDialog(mainframe,"Language",true);
		driver.setLayout(new GridBagLayout());

		final String [] choices = translator.getLanguageList();
		final JComboBox<String> language_options = new JComboBox<String>(choices);
		final JButton save = new JButton(">>>");

		GridBagConstraints c = new GridBagConstraints();
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=2;	c.gridx=0;	c.gridy=0;	driver.add(language_options,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=0;  driver.add(save,c);
		
		ActionListener driveButtons = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Object subject = e.getSource();
				// TODO prevent "close" icon.  Must press save to continue!
				if(subject == save) {
					translator.currentLanguage = choices[language_options.getSelectedIndex()];
					translator.saveConfig();
					driver.dispose();
				}
			}
		};

		save.addActionListener(driveButtons);

		driver.pack();
		driver.setVisible(true);
	}
	

	public void raisePen() {
		sendLineToRobot("G00 Z"+machineConfiguration.getPenUpString());
		driveControls.raisePen();
	}
	
	public void lowerPen() {
		sendLineToRobot("G00 Z" + machineConfiguration.getPenDownString());
		driveControls.lowerPen();
	}
	
	public boolean isRunning() { return isrunning; }
	public boolean isPaused() { return isPaused; }
	
	
	// TODO use a serviceLoader instead
	protected void LoadImageConverters() {
		image_converters = new ArrayList<Filter>();
		image_converters.add(new Filter_GeneratorZigZag(this,machineConfiguration,translator));
		image_converters.add(new Filter_GeneratorSpiral(this,machineConfiguration,translator));
		image_converters.add(new Filter_GeneratorCrosshatch(this,machineConfiguration,translator));
		image_converters.add(new Filter_GeneratorScanline(this,machineConfiguration,translator));
		image_converters.add(new Filter_GeneratorPulse(this,machineConfiguration,translator));
		image_converters.add(new Filter_GeneratorBoxes(this,machineConfiguration,translator));
		image_converters.add(new Filter_GeneratorRGB(this, machineConfiguration, translator));
		image_converters.add(new Filter_GenerateVoronoiStippling(this, machineConfiguration, translator));
	}
	
	protected void finalize() throws Throwable {
		//do finalization here
		EndLog();
		super.finalize(); //not necessary if extending Object.
	} 
	
	private void StartLog() {
		try {
			logToFile = new PrintWriter(new FileWriter("log.html"));
			Calendar cal = Calendar.getInstance();
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			logToFile.write("<h3>"+sdf.format(cal.getTime())+"</h3>\n");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void EndLog() {
		logToFile.close();
	}

	
	//  data access
	public ArrayList<String> getGcode() {
		return gcode.lines;
	}

	private void PlaySound(String url) {
		if(url.isEmpty()) return;
		
		try {
			Clip clip = AudioSystem.getClip();
			BufferedInputStream x = new BufferedInputStream(new FileInputStream(url));
			AudioInputStream inputStream = AudioSystem.getAudioInputStream(x);
			clip.open(inputStream);
			clip.start(); 
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println(e.getMessage());
		}
	}
	
	public void PlayConnectSound() {
		PlaySound(prefs.get("sound_connect", ""));
	}
	
	private void PlayDisconnectSound() {
		PlaySound(prefs.get("sound_disconnect", ""));
	}
	
	public void PlayConversionFinishedSound() {
		PlaySound(prefs.get("sound_conversion_finished", ""));
	}
	
	private void PlayDawingFinishedSound() {
		PlaySound(prefs.get("sound_drawing_finished", ""));
	}
		
	private void SetDrawStyle(int style) {
		prefs.putInt("Draw Style", style);
	}
	private int GetDrawStyle() {
		return prefs.getInt("Draw Style", 0);
	}
	
	
	private void HilbertCurve() {
		Filter_GeneratorHilbertCurve msg = new Filter_GeneratorHilbertCurve(this,machineConfiguration,translator);
		msg.Generate( GetTempDestinationFile() );
		TabToDraw();
	}
	
	
	private void TextToGCODE() {
		Filter_GeneratorYourMessageHere msg = new Filter_GeneratorYourMessageHere(this,machineConfiguration,translator);
		msg.Generate(GetTempDestinationFile() );
		TabToDraw();
	}
	

	// appends a message to the log tab and system out.
	public void Log(String msg) {
		// remove the 
		if(msg.indexOf(';') != -1 ) msg = msg.substring(0,msg.indexOf(';'));
		
		msg=msg.replace("\n", "<br>\n")+"\n";
		msg=msg.replace("\n\n","\n");
		logToFile.write(msg);
		logToFile.flush();

		try {
			kit.insertHTML(doc, doc.getLength(), msg, 0, 0, null);
			int over_length = doc.getLength() - msg.length() - 5000;
			doc.remove(0, over_length);
			//logPane.getVerticalScrollBar().setValue(logPane.getVerticalScrollBar().getMaximum());
		} catch (BadLocationException e) {
			// Do we care if it fails?
		} catch (IOException e) {
			// Do we care if it fails?
		}
	}

	public void ClearLog() {
		try {
			doc.replace(0, doc.getLength(), "", null);
			kit.insertHTML(doc, 0, "", 0, 0, null);
			//logPane.getVerticalScrollBar().setValue(logPane.getVerticalScrollBar().getMaximum());
		} catch (BadLocationException e) {
			// Do we care if it fails?
		} catch (IOException e) {
			// Do we care if it fails?
		}
	}

	/**
	 * Opens a file.  If the file can be opened, get a drawing time estimate, update recent files list, and repaint the preview tab.
	 * @param filename what file to open
	 */
	public void LoadGCode(String filename) {
		try {
			gcode.Load(filename);
		   	Log("<font color='green'>"+gcode.estimate_count + translator.get("LineSegments")
		   			+ "\n" + gcode.estimated_length + translator.get("Centimeters") + "\n"
		   			+ translator.get("EstimatedTime") + statusBar.formatTime((long)(gcode.estimated_time)) + "s.</font>\n");
	    }
	    catch(IOException e) {
	    	Log("<span style='color:red'>"+translator.get("FileNotOpened") + e.getLocalizedMessage()+"</span>\n");
	    	recentFiles.remove(filename);
	    	updateMenuBar();
	    	return;
	    }
	    
	    previewPane.setGCode(gcode.lines);
	    Halt();
	}
	
	public String GetTempDestinationFile() {
		return System.getProperty("user.dir")+"/temp.ngc";
	}
	
	
	protected boolean ChooseImageConversionOptions(boolean isDXF) {
		final JDialog driver = new JDialog(mainframe,translator.get("ConversionOptions"),true);
		driver.setLayout(new GridBagLayout());
		
		final String[] choices = machineConfiguration.getKnownMachineNames();
		final JComboBox<String> machine_choice = new JComboBox<String>(choices);
		machine_choice.setSelectedIndex(machineConfiguration.getCurrentMachineIndex());
		
		final JSlider input_paper_margin = new JSlider(JSlider.HORIZONTAL, 0, 50, 100-(int)(machineConfiguration.paper_margin*100));
		input_paper_margin.setMajorTickSpacing(10);
		input_paper_margin.setMinorTickSpacing(5);
		input_paper_margin.setPaintTicks(false);
		input_paper_margin.setPaintLabels(true);
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JCheckBox reverse_h = new JCheckBox(translator.get("FlipForGlass"));
		reverse_h.setSelected(machineConfiguration.reverseForGlass);
		final JButton cancel = new JButton(translator.get("Cancel"));
		final JButton save = new JButton(translator.get("Start"));

		String [] filter_names = new String[image_converters.size()];
		Iterator<Filter> fit = image_converters.iterator();
		int i=0;
		while(fit.hasNext()) {
			Filter f = fit.next();
			filter_names[i++] = f.GetName();
		}
		
		final JComboBox<String> input_draw_style = new JComboBox<String>(filter_names);
		input_draw_style.setSelectedIndex(GetDrawStyle());
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		int y=0;
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=y  ;  driver.add(new JLabel(translator.get("MenuLoadMachineConfig")),c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=2;	c.gridx=1;	c.gridy=y++;  driver.add(machine_choice,c);

		if(!isDXF) {
			c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=y;  driver.add(new JLabel(translator.get("ConversionStyle")),c);
			c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;	c.gridy=y++;	driver.add(input_draw_style,c);
		}
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=y  ;  driver.add(new JLabel(translator.get("PaperMargin")),c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=y++;  driver.add(input_paper_margin,c);
		
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;  c.gridx=1;  c.gridy=y++;  driver.add(reverse_h,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=y  ;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=y++;  driver.add(cancel,c);

		startConvertingNow = false;
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == save) {
						long new_uid = Long.parseLong( choices[machine_choice.getSelectedIndex()] );
						machineConfiguration.LoadConfig(new_uid);
						SetDrawStyle(input_draw_style.getSelectedIndex());
						machineConfiguration.paper_margin=(100-input_paper_margin.getValue())*0.01;
						machineConfiguration.reverseForGlass=reverse_h.isSelected();
						machineConfiguration.SaveConfig();
						
						// if we aren't connected, don't show the new 
						if(connectionToRobot!=null && !connectionToRobot.isRobotConfirmed()) {
							// Force update of graphics layout.
							previewPane.updateMachineConfig();
							// update window title
							mainframe.setTitle(translator.get("TitlePrefix") 
									+ Long.toString(machineConfiguration.robot_uid) 
									+ translator.get("TitleNotConnected"));
						}
						startConvertingNow=true;
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};
			
		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
		
		return startConvertingNow;
	}
	
	protected boolean LoadDXF(String filename) {
		if( ChooseImageConversionOptions(true) == false ) return false;

        // where to save temp output file?
		final String destinationFile = GetTempDestinationFile();
		final String srcFile = filename;
		
		TabToLog();
		
		final ProgressMonitor pm = new ProgressMonitor(null, translator.get("Converting"), "", 0, 100);
		pm.setProgress(0);
		pm.setMillisToPopup(0);
		
		final SwingWorker<Void,Void> s = new SwingWorker<Void,Void>() {
			public boolean ok=false;
			
			@SuppressWarnings("unchecked")
			@Override
			public Void doInBackground() {
				Log("<font color='green'>"+translator.get("Converting")+" "+destinationFile+"</font>\n");

				Parser parser = ParserBuilder.createDefaultParser();

				double dxf_x2=0;
				double dxf_y2=0;
				OutputStreamWriter out=null;

				try {
					out = new OutputStreamWriter(new FileOutputStream(destinationFile),"UTF-8");
					DrawingTool tool = machineConfiguration.GetCurrentTool();
					out.write(machineConfiguration.GetConfigLine()+";\n");
					out.write(machineConfiguration.GetBobbinLine()+";\n");
					out.write("G00 G90;\n");
					tool.WriteChangeTo(out);
					tool.WriteOff(out);
					
					parser.parse(srcFile, DXFParser.DEFAULT_ENCODING);
					DXFDocument doc = parser.getDocument();
					Bounds b = doc.getBounds();
					double width = b.getMaximumX() - b.getMinimumX();
					double height = b.getMaximumY() - b.getMinimumY();
					double cx = ( b.getMaximumX() + b.getMinimumX() ) / 2.0f;
					double cy = ( b.getMaximumY() + b.getMinimumY() ) / 2.0f;
					double sy = machineConfiguration.GetPaperHeight()*10/height;
					double sx = machineConfiguration.GetPaperWidth()*10/width;
					double scale = (sx<sy? sx:sy ) * machineConfiguration.paper_margin;
					sx = scale * (machineConfiguration.reverseForGlass? -1 : 1);
					// count all entities in all layers
					Iterator<DXFLayer> layer_iter = (Iterator<DXFLayer>)doc.getDXFLayerIterator();
					int entity_total=0;
					int entity_count=0;
					while(layer_iter.hasNext()) {
						DXFLayer layer = (DXFLayer)layer_iter.next();
						Log("<font color='yellow'>Found layer "+layer.getName()+"</font>\n");
						Iterator<String> entity_iter = (Iterator<String>)layer.getDXFEntityTypeIterator();
						while(entity_iter.hasNext()) {
							String entity_type = (String)entity_iter.next();
							List<DXFEntity> entity_list = (List<DXFEntity>)layer.getDXFEntities(entity_type);
							Log("<font color='yellow'>+ Found "+entity_list.size()+" of type "+entity_type+"</font>\n");
							entity_total+=entity_list.size();
						}
					}
					// set the progress meter
					pm.setMinimum(0);
					pm.setMaximum(entity_total);
							
					// convert each entity
					layer_iter = doc.getDXFLayerIterator();
					while(layer_iter.hasNext()) {
						DXFLayer layer = (DXFLayer)layer_iter.next();

						Iterator<String> entity_type_iter = (Iterator<String>)layer.getDXFEntityTypeIterator();
						while(entity_type_iter.hasNext()) {
							String entity_type = (String)entity_type_iter.next();
							List<DXFEntity> entity_list = layer.getDXFEntities(entity_type);
							
							if(entity_type.equals(DXFConstants.ENTITY_TYPE_LINE)) {
								for(int i=0;i<entity_list.size();++i) {
									pm.setProgress(entity_count++);
									DXFLine entity = (DXFLine)entity_list.get(i);
									Point start = entity.getStartPoint();
									Point end = entity.getEndPoint();

									double x=(start.getX()-cx)*sx;
									double y=(start.getY()-cy)*sy;
									double x2=(end.getX()-cx)*sx;
									double y2=(end.getY()-cy)*sy;
									
									// is it worth drawing this line?
									double dx = x2-x;
									double dy = y2-y;
									if(dx*dx+dy*dy < tool.GetDiameter()/2.0) {
										continue;
									}
									
									dx = dxf_x2 - x;
									dy = dxf_y2 - y;

									if(dx*dx+dy*dy > tool.GetDiameter()/2.0) {
										if(tool.DrawIsOn()) {
											tool.WriteOff(out);
										}
										tool.WriteMoveTo(out, (float)x,(float)y);
									}
									if(tool.DrawIsOff()) {
										tool.WriteOn(out);
									}
									tool.WriteMoveTo(out, (float)x2,(float)y2);
									dxf_x2=x2;
									dxf_y2=y2;
								}
							} else if(entity_type.equals(DXFConstants.ENTITY_TYPE_SPLINE)) {
								for(int i=0;i<entity_list.size();++i) {
									pm.setProgress(entity_count++);
									DXFSpline entity = (DXFSpline)entity_list.get(i);
									entity.setLineWeight(30);
									DXFPolyline polyLine = DXFSplineConverter.toDXFPolyline(entity);
									boolean first=true;
									for(int j=0;j<polyLine.getVertexCount();++j) {
										DXFVertex v = polyLine.getVertex(j);
										double x = (v.getX()-cx)*sx;
										double y = (v.getY()-cy)*sy;
										double dx = dxf_x2 - x;
										double dy = dxf_y2 - y;
										
										if(first==true) {
											first=false;
											if(dx*dx+dy*dy > tool.GetDiameter()/2.0) {
												// line does not start at last tool location, lift and move.
												if(tool.DrawIsOn()) {
													tool.WriteOff(out);
												}
												tool.WriteMoveTo(out, (float)x,(float)y);
											}
											// else line starts right here, do nothing.
										} else {
											// not the first point, draw.
											if(tool.DrawIsOff()) tool.WriteOn(out);
											if(j<polyLine.getVertexCount()-1 && dx*dx+dy*dy<tool.GetDiameter()/2.0) continue;  // less than 1mm movement?  Skip it. 
											tool.WriteMoveTo(out, (float)x,(float)y);
										}
										dxf_x2=x;
										dxf_y2=y;
									}
								}
							} else if(entity_type.equals(DXFConstants.ENTITY_TYPE_POLYLINE)) {
								for(int i=0;i<entity_list.size();++i) {
									pm.setProgress(entity_count++);
									DXFPolyline entity = (DXFPolyline)entity_list.get(i);
									boolean first=true;
									for(int j=0;j<entity.getVertexCount();++j) {
										DXFVertex v = entity.getVertex(j);
										double x = (v.getX()-cx)*sx;
										double y = (v.getY()-cy)*sy;
										double dx = dxf_x2 - x;
										double dy = dxf_y2 - y;
										
										if(first==true) {
											first=false;
											if(dx*dx+dy*dy > tool.GetDiameter()/2.0) {
												// line does not start at last tool location, lift and move.
												if(tool.DrawIsOn()) {
													tool.WriteOff(out);
												}
												tool.WriteMoveTo(out, (float)x,(float)y);
											}
											// else line starts right here, do nothing.
										} else {
											// not the first point, draw.
											if(tool.DrawIsOff()) tool.WriteOn(out);
											if(j<entity.getVertexCount()-1 && dx*dx+dy*dy<tool.GetDiameter()/2.0) continue;  // less than 1mm movement?  Skip it. 
											tool.WriteMoveTo(out, (float)x,(float)y);
										}
										dxf_x2=x;
										dxf_y2=y;
									}
								}
							}
						}
					}

					// entities finished.  Close up file.
					tool.WriteOff(out);
					tool.WriteMoveTo(out, 0, 0);
					
					ok=true;
				} catch(IOException e) {
					e.printStackTrace();
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					try {
						if(out!=null) out.close();
					} catch(IOException e) {
						e.printStackTrace();
					}
						
				}
				
				pm.setProgress(100);
			    return null;
			}
			
			@Override
			public void done() {
				pm.close();
				Log("<font color='green'>"+translator.get("Finished")+"</font>\n");
				PlayConversionFinishedSound();
				if(ok) {
					LoadGCode(destinationFile);
					TabToDraw();
				}
				Halt();
			}
		};
		
		s.addPropertyChangeListener(new PropertyChangeListener() {
		    // Invoked when task's progress property changes.
		    public void propertyChange(PropertyChangeEvent evt) {
		        if ("progress" == evt.getPropertyName() ) {
		            int progress = (Integer) evt.getNewValue();
		            pm.setProgress(progress);
		            String message = String.format("%d%%\n", progress);
		            pm.setNote(message);
		            if(s.isDone()) {
	                	Log("<font color='green'>"+translator.get("Finished")+"</font>\n");
		            } else if (s.isCancelled() || pm.isCanceled()) {
		                if (pm.isCanceled()) {
		                    s.cancel(true);
		                }
	                    Log("<font color='green'>"+translator.get("Cancelled")+"</font>\n");
		            }
		        }
		    }
		});
		
		s.execute();
		
		return true;
	}
	
	
	public boolean LoadImage(String filename) {
        // where to save temp output file?
		final String sourceFile = filename;
		final String destinationFile = GetTempDestinationFile();
		
		LoadImageConverters();
		if( ChooseImageConversionOptions(false) == false ) return false;

		final ProgressMonitor pm = new ProgressMonitor(null, translator.get("Converting"), "", 0, 100);
		pm.setProgress(0);
		pm.setMillisToPopup(0);
		
		final SwingWorker<Void,Void> s = new SwingWorker<Void,Void>() {
			@Override
			public Void doInBackground() {
				// read in image
				BufferedImage img;
				try {
					Log("<font color='green'>"+translator.get("Converting")+" "+destinationFile+"</font>\n");
					// convert with style
					img = ImageIO.read(new File(sourceFile));
					int style = GetDrawStyle();
					Filter f = image_converters.get(style);
					TabToLog();
					f.SetParent(this);
					f.SetProgressMonitor(pm);
					f.SetDestinationFile(destinationFile);
					f.Convert(img);
					TabToDraw();
			        previewPane.ZoomToFitPaper();
				}
				catch(IOException e) {
					Log("<font color='red'>"+translator.get("Failed")+e.getLocalizedMessage()+"</font>\n");
					recentFiles.remove(sourceFile);
					updateMenuBar();
				}

				pm.setProgress(100);
			    return null;
			}
			
			@Override
			public void done() {
				pm.close();
				Log("<font color='green'>"+translator.get("Finished")+"</font>\n");
				PlayConversionFinishedSound();
				LoadGCode(destinationFile);
			}
		};
		
		s.addPropertyChangeListener(new PropertyChangeListener() {
		    // Invoked when task's progress property changes.
		    public void propertyChange(PropertyChangeEvent evt) {
		        if ("progress" == evt.getPropertyName() ) {
		            int progress = (Integer) evt.getNewValue();
		            pm.setProgress(progress);
		            String message = String.format("%d%%.\n", progress);
		            pm.setNote(message);
		            if(s.isDone()) {
	                	Log("<font color='green'>"+translator.get("Finished")+"</font>\n");
		            } else if (s.isCancelled() || pm.isCanceled()) {
		                if (pm.isCanceled()) {
		                    s.cancel(true);
		                }
	                    Log("<font color='green'>"+translator.get("Cancelled")+"</font>\n");
		            }
		        }
		    }
		});
		
		s.execute();
		
		return true;
	}
	
	
	public boolean IsFileLoaded() {
		return ( gcode.fileOpened && gcode.lines != null && gcode.lines.size() > 0 );
	}
	
	public boolean IsFileGcode(String filename) {
		String ext=filename.substring(filename.lastIndexOf('.'));
    	return (ext.equalsIgnoreCase(".ngc") || ext.equalsIgnoreCase(".gc"));
	}
	
	public boolean IsFileDXF(String filename) {
		String ext=filename.substring(filename.lastIndexOf('.'));
    	return (ext.equalsIgnoreCase(".dxf"));
	}
	
	public boolean IsFileImage(String filename) {
		String ext=filename.substring(filename.lastIndexOf('.'));
    	return ext.equalsIgnoreCase(".jpg")
    			|| ext.equalsIgnoreCase(".png")
    			|| ext.equalsIgnoreCase(".bmp")
    			|| ext.equalsIgnoreCase(".gif");
	}
	
	// User has asked that a file be opened.
	public void OpenFileOnDemand(String filename) {
 		Log("<font color='green'>" + translator.get("OpeningFile") + filename + "...</font>\n");

	   	if(IsFileGcode(filename)) {
			LoadGCode(filename);
    	} else if(IsFileDXF(filename)) {
    		LoadDXF(filename);
    	} else if(IsFileImage(filename)) {
    		LoadImage(filename);
    	} else {
    		Log("<font color='red'>"+translator.get("UnknownFileType")+"</font>\n");
    	}

	   	// TODO: if succeeded
	   	recentFiles.add(filename);
		updateMenuBar();
    	statusBar.Clear();
	}

	// creates a file open dialog. If you don't cancel it opens that file.
	public void OpenFileDialog() {
	    // Note: source for ExampleFileFilter can be found in FileChooserDemo,
	    // under the demo/jfc directory in the Java 2 SDK, Standard Edition.
		String s = recentFiles.get(0);
		String filename = (s.length()>0) ? filename = s : "";

		FileFilter filterGCODE = new FileNameExtensionFilter(translator.get("FileTypeGCode"), "ngc");
		FileFilter filterImage = new FileNameExtensionFilter(translator.get("FileTypeImage"), "jpg", "jpeg", "png", "wbmp", "bmp", "gif");
		FileFilter filterDXF   = new FileNameExtensionFilter(translator.get("FileTypeDXF"), "dxf");
		 
		JFileChooser fc = new JFileChooser(new File(filename));
		fc.addChoosableFileFilter(filterImage);
		fc.addChoosableFileFilter(filterDXF);
		fc.addChoosableFileFilter(filterGCODE);
		fc.setFileFilter(filterImage);
	    if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
	    	String selectedFile=fc.getSelectedFile().getAbsolutePath();

	    	// if machine is not yet calibrated
	    	if(machineConfiguration.IsPaperConfigured() == false) {
	    		JOptionPane.showMessageDialog(null,translator.get("SetPaperSize"));
	    		return;
	    	}
	    	OpenFileOnDemand(selectedFile);
	    }
	}
	
	private void SaveFileDialog() {
	    // Note: source for ExampleFileFilter can be found in FileChooserDemo,
	    // under the demo/jfc directory in the Java 2 SDK, Standard Edition.
		String s = recentFiles.get(0);
		String filename = (s.length()>0) ? filename = s : "";

		FileFilter filterGCODE = new FileNameExtensionFilter(translator.get("FileTypeGCode"), "ngc");
		
		JFileChooser fc = new JFileChooser(new File(filename));
		fc.addChoosableFileFilter(filterGCODE);
	    if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
	    	String selectedFile=fc.getSelectedFile().getAbsolutePath();

			if(!selectedFile.toLowerCase().endsWith(".ngc")) {
				selectedFile+=".ngc";
			}

	    	try {
	    		gcode.Save(selectedFile);
	    	}
		    catch(IOException e) {
		    	Log("<span style='color:red'>"+translator.get("Failed")+e.getMessage()+"</span>\n");
		    	return;
		    }
	    }
	}
	
	public void GoHome() {
		sendLineToRobot("G00 F" + feed_rate + " X0 Y0");
	}
	
	private String SelectFile() {
		JFileChooser choose = new JFileChooser();
	    int returnVal = choose.showOpenDialog(this);
	    if (returnVal == JFileChooser.APPROVE_OPTION) {
	        File file = choose.getSelectedFile();
	        return file.getAbsolutePath();
	    } else {
	        //System.out.println("File access cancelled by user.");
		    return "";
	    }
	}
	
	// Adjust sound preferences
	protected void AdjustSounds() {
		final JDialog driver = new JDialog(mainframe,translator.get("MenuSoundsTitle"),true);
		driver.setLayout(new GridBagLayout());
		
		final JTextField sound_connect = new JTextField(prefs.get("sound_connect",""),32);
		final JTextField sound_disconnect = new JTextField(prefs.get("sound_disconnect", ""),32);
		final JTextField sound_conversion_finished = new JTextField(prefs.get("sound_conversion_finished", ""),32);
		final JTextField sound_drawing_finished = new JTextField(prefs.get("sound_drawing_finished", ""),32);

		final JButton change_sound_connect = new JButton(translator.get("MenuSoundsConnect"));
		final JButton change_sound_disconnect = new JButton(translator.get("MenuSoundsDisconnect"));
		final JButton change_sound_conversion_finished = new JButton(translator.get("MenuSoundsFinishConvert"));
		final JButton change_sound_drawing_finished = new JButton(translator.get("MenuSoundsFinishDraw"));
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JButton cancel = new JButton(translator.get("Cancel"));
		final JButton save = new JButton(translator.get("Save"));
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=3;  driver.add(change_sound_connect,c);								c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=3;  driver.add(sound_connect,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=4;  driver.add(change_sound_disconnect,c);							c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=4;  driver.add(sound_disconnect,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=5;  driver.add(change_sound_conversion_finished,c);					c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=5;  driver.add(sound_conversion_finished,c);
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=0;  c.gridy=6;  driver.add(change_sound_drawing_finished,c);					c.anchor=GridBagConstraints.WEST;	c.gridwidth=3;	c.gridx=1;  c.gridy=6;  driver.add(sound_drawing_finished,c);
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=12;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=12;  driver.add(cancel,c);
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == change_sound_connect) sound_connect.setText(SelectFile());
					if(subject == change_sound_disconnect) sound_disconnect.setText(SelectFile());
					if(subject == change_sound_conversion_finished) sound_conversion_finished.setText(SelectFile());
					if(subject == change_sound_drawing_finished) sound_drawing_finished.setText(SelectFile());

					if(subject == save) {
						//allowMetrics = allow_metrics.isSelected();
						prefs.put("sound_connect",sound_connect.getText());
						prefs.put("sound_disconnect",sound_disconnect.getText());
						prefs.put("sound_conversion_finished",sound_conversion_finished.getText());
						prefs.put("sound_drawing_finished",sound_drawing_finished.getText());
						machineConfiguration.SaveConfig();
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};

		change_sound_connect.addActionListener(driveButtons);
		change_sound_disconnect.addActionListener(driveButtons);
		change_sound_conversion_finished.addActionListener(driveButtons);
		change_sound_drawing_finished.addActionListener(driveButtons);
			
		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
	}

    // Adjust graphics preferences	
	protected void AdjustGraphics() {
		final Preferences graphics_prefs = Preferences.userRoot().node("DrawBot").node("Graphics");
		
		final JDialog driver = new JDialog(mainframe,translator.get("MenuGraphicsTitle"),true);
		driver.setLayout(new GridBagLayout());
		
		//final JCheckBox allow_metrics = new JCheckBox(String.valueOf("I want to add the distance drawn to the // total"));
		//allow_metrics.setSelected(allowMetrics);
		
		final JCheckBox show_pen_up = new JCheckBox(translator.get("MenuGraphicsPenUp"));
		final JCheckBox antialias_on = new JCheckBox(translator.get("MenuGraphicsAntialias"));
		final JCheckBox speed_over_quality = new JCheckBox(translator.get("MenuGraphicsSpeedVSQuality"));
		final JCheckBox draw_all_while_running = new JCheckBox(translator.get("MenuGraphicsDrawWhileRunning"));

		show_pen_up.setSelected(graphics_prefs.getBoolean("show pen up", false));
		antialias_on.setSelected(graphics_prefs.getBoolean("antialias", true));
		speed_over_quality.setSelected(graphics_prefs.getBoolean("speed over quality", true));
		draw_all_while_running.setSelected(graphics_prefs.getBoolean("Draw all while running", true));
		
		final JButton cancel = new JButton(translator.get("Cancel"));
		final JButton save = new JButton(translator.get("Save"));
		
		GridBagConstraints c = new GridBagConstraints();
		//c.gridwidth=4; 	c.gridx=0;  c.gridy=0;  driver.add(allow_metrics,c);

		int y=0;
		
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(show_pen_up,c);  y++;
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(draw_all_while_running,c);  y++;
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(antialias_on,c);  y++;
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=1;  c.gridy=y;  driver.add(speed_over_quality,c);  y++;
		
		c.anchor=GridBagConstraints.EAST;	c.gridwidth=1;	c.gridx=2;  c.gridy=y;  driver.add(save,c);
		c.anchor=GridBagConstraints.WEST;	c.gridwidth=1;	c.gridx=3;  c.gridy=y;  driver.add(cancel,c);
		
		ActionListener driveButtons = new ActionListener() {
			  public void actionPerformed(ActionEvent e) {
					Object subject = e.getSource();
					if(subject == save) {
						//allowMetrics = allow_metrics.isSelected();
						graphics_prefs.putBoolean("show pen up", show_pen_up.isSelected());
						graphics_prefs.putBoolean("antialias", antialias_on.isSelected());
						graphics_prefs.putBoolean("speed over quality", speed_over_quality.isSelected());
						graphics_prefs.putBoolean("Draw all while running", draw_all_while_running.isSelected());

						previewPane.setShowPenUp(show_pen_up.isSelected());
						driver.dispose();
					}
					if(subject == cancel) {
						driver.dispose();
					}
			  }
		};

		save.addActionListener(driveButtons);
		cancel.addActionListener(driveButtons);
	    driver.getRootPane().setDefaultButton(save);
		driver.pack();
		driver.setVisible(true);
	}
	
	// Send the machine configuration to the robot
	public void SendConfig() {
		if(connectionToRobot!=null && !connectionToRobot.isRobotConfirmed()) return;
		
		// Send a command to the robot with new configuration values
		sendLineToRobot(machineConfiguration.GetConfigLine());
		sendLineToRobot(machineConfiguration.GetBobbinLine());
		sendLineToRobot("G92 X0 Y0");
	}
	
	
	// Take the next line from the file and send it to the robot, if permitted. 
	public void SendFileCommand() {
		if(isrunning==false || isPaused==true || gcode.fileOpened==false ||
				(connectionToRobot!=null && connectionToRobot.isRobotConfirmed()==false) || gcode.linesProcessed>=gcode.linesTotal) return;
		
		String line;
		do {			
			// are there any more commands?
			// TODO: find out how far the pen moved each line and add it to the distance total.
			int line_number = gcode.linesProcessed;
			gcode.linesProcessed++;
			line=gcode.lines.get(line_number).trim();

			// TODO catch pen up/down status here
			if(line.contains("G00 Z"+machineConfiguration.getPenUpString())) {
				driveControls.raisePen();
			}
			if(line.contains("G00 Z"+machineConfiguration.getPenDownString())) {
				driveControls.lowerPen();
			}
			

			if(line.length()>3) {
				line="N"+line_number+" "+line;
			}
			line += GenerateChecksum(line);
			
			previewPane.setLinesProcessed(gcode.linesProcessed);
			statusBar.SetProgress(gcode.linesProcessed, gcode.linesTotal);
			// loop until we find a line that gets sent to the robot, at which point we'll
			// pause for the robot to respond.  Also stop at end of file.
		} while(ProcessLine(line) && gcode.linesProcessed<gcode.linesTotal);
		
		if(gcode.linesProcessed==gcode.linesTotal) {
			// end of file
			PlayDawingFinishedSound();
			Halt();
			SayHooray();
		}
	}
	
	
	private void SayHooray() {
		long num_lines = gcode.linesProcessed;
		
		JOptionPane.showMessageDialog(null,
				translator.get("Finished") +
				num_lines +
				translator.get("LineSegments") + 
				"\n" +
				statusBar.GetElapsed() +
				"\n" +
				translator.get("SharePromo")
				);
	}
	
	
	private void ChangeToTool(String changeToolString) {
		int i = Integer.decode(changeToolString);
		
		String [] toolNames = machineConfiguration.getToolNames();
		
		if(i<0 || i>toolNames.length) {
			Log("<span style='color:red'>" + translator.get("InvalidTool") + i +"</span>");
			i=0;
		}
		JOptionPane.showMessageDialog(null, translator.get("ChangeToolPrefix") + toolNames[i] + translator.get("ChangeToolPostfix"));
	}
	
	
	/**
	 * removes comments, processes commands drawbot shouldn't have to handle.
	 * @param line command to send
	 * @return true if the robot is ready for another command to be sent.
	 */
	public boolean ProcessLine(String line) {
		if(connectionToRobot == null || !connectionToRobot.isRobotConfirmed() || !isrunning) return false;

		// tool change request?
		String [] tokens = line.split("(\\s|;)");

		// tool change?
		if(Arrays.asList(tokens).contains("M06") || Arrays.asList(tokens).contains("M6")) {
			for(int i=0;i<tokens.length;++i) {
				if(tokens[i].startsWith("T")) {
					ChangeToTool(tokens[i].substring(1));
				}
			}
		}
		
		// end of program?
		if(tokens[0]=="M02" || tokens[0]=="M2" || tokens[0]=="M30") {
			PlayDawingFinishedSound();
			Halt();
			return false;
		}
		
		
		// send relevant part of line to the robot
		sendLineToRobot(line);
		
		return false;
	}
	
	
	protected String GenerateChecksum(String line) {
		byte checksum=0;
		
		for( int i=0; i<line.length(); ++i ) {
			checksum ^= line.charAt(i);
		}
		
		return "*"+((int)checksum);
	}
	

	/**
	 * Sends a single command the robot.  Could be anything.
	 * @param line command to send.
	 * @return <code>true</code> if command was sent to the robot; <code>false</code> otherwise.
	 */
	public boolean sendLineToRobot(String line) {
		if(connectionToRobot==null || !connectionToRobot.isRobotConfirmed()) return false;
		
		if(line.trim().equals("")) return false;
		String reportedline = line;
		if(line.contains(";")) {
			String [] lines = line.split(";");
			reportedline = lines[0];
		}
		Log("<font color='white'>" + reportedline + "</font>");
		line += "\n";
		
		try {
			connectionToRobot.sendMessage(line);
		}
		catch(Exception e) {
			Log(e.getMessage());
			return false;
		}
		return true;
	}
	
	/**
	 * stop sending file commands to the robot.
	 * TODO add an e-stop command?
	 */
	public void Halt() {
		isrunning=false;
		isPaused=false;
	    previewPane.setLinesProcessed(0);
		previewPane.setRunning(isrunning);
		updateMenuBar();
	}
	
	public void startAt(long lineNumber) {
		gcode.linesProcessed=0;
		sendLineToRobot("M110 N"+gcode.linesProcessed);
		previewPane.setLinesProcessed(gcode.linesProcessed);
		startDrawing();
	}
	
	public void pause() {
		isPaused=true;
	}
	
	public void unPause() {
		isPaused=false;
	}

	private void startDrawing() {
		isPaused=false;
		isrunning=true;
		previewPane.setRunning(isrunning);
		updateMenuBar();
		statusBar.Start();
		SendFileCommand();
	}
	
	// The user has done something.  respond to it.
	public void actionPerformed(ActionEvent e) {
		Object subject = e.getSource();
		
		if( subject == buttonZoomIn ) {
			previewPane.ZoomIn();
			return;
		}
		if( subject == buttonZoomOut ) {
			previewPane.ZoomOut();
			return;
		}
		if( subject == buttonZoomToFit ) {
			previewPane.ZoomToFitPaper();
			return;
		}
		if( subject == buttonOpenFile ) {
			OpenFileDialog();
			return;
		}
		if( subject == buttonHilbertCurve ) {
			HilbertCurve();
			return;
		}
		if( subject == buttonText2GCODE ) {
			TextToGCODE();
			return;
		}
		if( subject == buttonRescan ) {
			connectionManager.listConnections();
			updateMenuBar();
			return;
		}
		if( subject == buttonDisconnect ) {
			connectionToRobot.closeConnection();
			connectionToRobot=null;
			ClearLog();
			previewPane.setConnected(false);
			updateMenuBar();
			PlayDisconnectSound();

			// update window title
			mainframe.setTitle(translator.get("TitlePrefix")
					+ Long.toString(machineConfiguration.robot_uid)
					+ translator.get("TitleNotConnected"));
			return;
		}
		if( subject == buttonAdjustSounds ) {
			AdjustSounds();
			return;
		}
		if( subject == buttonAdjustGraphics ) {
			AdjustGraphics();
			return;
		}
		if( subject == buttonAdjustLanguage ) {
			chooseLanguage();
			updateMenuBar();
		}
		if( subject == buttonAdjustMachineSize ) {
			machineConfiguration.AdjustMachineSize();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonAdjustPulleySize ) {
			machineConfiguration.AdjustPulleySize();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonChangeTool ) {
			machineConfiguration.ChangeTool();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonAdjustTool ) {
			machineConfiguration.AdjustTool();
			previewPane.updateMachineConfig();
			return;
		}
		if( subject == buttonJogMotors ) {
			JogMotors();
			return;
		}
		if( subject == buttonAbout ) {
            final String aboutHtml = getAboutHtmlFromMultilingualString();
			final JTextComponent bottomText = createHyperlinkListenableJEditorPane(aboutHtml);
			ImageIcon icon = null;
			try {
				icon = new ImageIcon(IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("logo.png")));
			} catch (IOException exceptionLoadingIconImage) {
				System.err.print(exceptionLoadingIconImage);
			}
		    JOptionPane.showMessageDialog(null, bottomText, "About FIXME", JOptionPane.INFORMATION_MESSAGE, icon);
			return;
		}
		if( subject == buttonCheckForUpdate ) {
			CheckForUpdate();
			return;
		}
		
		if( subject == buttonSaveFile ) {
			SaveFileDialog();
			return;
		}
		
		if( subject == buttonExit ) {
			System.exit(0);  // TODO: be more graceful?
			return;
		}
		
		int i;
		for(i=0;i<10;++i) {
			if(subject == buttonRecent[i]) {
				OpenFileOnDemand(recentFiles.get(i));
				return;
			}
		}

		String [] connections = connectionManager.listConnections(); 
		for(i=0;i<connections.length;++i) {
			if(subject == buttonPorts[i]) {

				Log("<font color='green'>" + translator.get("ConnectingTo") + connections[i] + "...</font>\n");

				connectionToRobot = connectionManager.openConnection(connections[i]);
				if(connectionToRobot!=null) {
					Log("<span style='color:green'>" + translator.get("PortOpened") + "</span>\n");
					updateMenuBar();
					PlayConnectSound();
				} else {
					Log("<span style='color:red'>" + translator.get("PortOpenFailed") + "</span>\n");
				}
				return;
			}
		}
	}

	/**
	 * 
     * <p>
     * Uses {@link java.lang.StringBuilder#append(String)} to create an internationalization supported {@code String}
     * representing the About Message Dialog's HTML.
     * </p>
     *
     * <p>
     * The summation of {@link String#length()} for each of the respective values retrieved with the
     * {@code "AboutHTMLBeforeVersionNumber"}, and {@code "AboutHTMLAfterVersionNumber"} {@link MultilingualSupport} keys,
     * in conjunction with {@link MainGUI#version} is calculated for use with {@link java.lang.StringBuilder#StringBuilder(int)}.
     * </p>
     *
     * @return An HTML string used for the About Message Dialog.
     */
    private String getAboutHtmlFromMultilingualString() {
        final String aboutHtmlBeforeVersionNumber = translator.get("AboutHTMLBeforeVersionNumber");
        final String aboutHmlAfterVersionNumber = translator.get("AboutHTMLAfterVersionNumber");
        final int aboutHTMLBeforeVersionNumberLength = aboutHtmlBeforeVersionNumber.length();
        final int versionNumberStringLength = version.length();
        final int aboutHtmlAfterVersionNumberLength = aboutHmlAfterVersionNumber.length();
        final int aboutHtmlStringBuilderCapacity = aboutHTMLBeforeVersionNumberLength + versionNumberStringLength + aboutHtmlAfterVersionNumberLength;
        final StringBuilder aboutHtmlStringBuilder = new StringBuilder(aboutHtmlStringBuilderCapacity);
        aboutHtmlStringBuilder.append(aboutHtmlBeforeVersionNumber);
        aboutHtmlStringBuilder.append(version);
        aboutHtmlStringBuilder.append(aboutHmlAfterVersionNumber);
        return aboutHtmlStringBuilder.toString();
    }

    /**
	 * 
	 * @param html String of valid HTML.
	 * @return a 
	 */
	private JTextComponent createHyperlinkListenableJEditorPane(String html) {
		final JEditorPane bottomText = new JEditorPane();
		bottomText.setContentType("text/html");
		bottomText.setEditable(false);
		bottomText.setText(html);
		bottomText.setOpaque(false);
		final HyperlinkListener hyperlinkListener = new HyperlinkListener() {
			public void hyperlinkUpdate(HyperlinkEvent hyperlinkEvent) {
				if (hyperlinkEvent.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
					if (Desktop.isDesktopSupported()) {
						try {
							Desktop.getDesktop().browse(hyperlinkEvent.getURL().toURI());
						} catch (IOException | URISyntaxException exception) {
							// FIXME Auto-generated catch block
							exception.printStackTrace();
						}
					}

				}
			}
		};
		bottomText.addHyperlinkListener(hyperlinkListener);
		return bottomText;
	}

    // settings menu
	public JPanel SettingsPanel() {
		JPanel panel = new JPanel(new GridLayout(0,1));

        // TODO: move all these into a pop-up menu with tabs
        buttonAdjustMachineSize = new JButton(translator.get("MenuSettingsMachine"));
        buttonAdjustMachineSize.addActionListener(this);
        panel.add(buttonAdjustMachineSize);

        buttonAdjustPulleySize = new JButton(translator.get("MenuAdjustPulleys"));
        buttonAdjustPulleySize.addActionListener(this);
		panel.add(buttonAdjustPulleySize);
        
        buttonJogMotors = new JButton(translator.get("JogMotors"));
        buttonJogMotors.addActionListener(this);
		panel.add(buttonJogMotors);

        panel.add(new JSeparator());
        
        buttonChangeTool = new JButton(translator.get("MenuSelectTool"));
        buttonChangeTool.addActionListener(this);
        panel.add(buttonChangeTool);

        buttonAdjustTool = new JButton(translator.get("MenuAdjustTool"));
        buttonAdjustTool.addActionListener(this);
        panel.add(buttonAdjustTool);
        
        return panel;
	}
	
	
	public JPanel ProcessImages() {
		JPanel driver = new JPanel(new GridLayout(0,1));

        // File conversion menu
        buttonOpenFile = new JButton(translator.get("MenuOpenFile"));
        buttonOpenFile.addActionListener(this);
        driver.add(buttonOpenFile);
/*        
        subMenu = new JMenu(translator.get("MenuConvertImage"));
        group = new ButtonGroup();

	        // list recent files
	        if(recentFiles != null && recentFiles.length>0) {	        	
	        	for(i=0;i<recentFiles.length;++i) {
	        		if(recentFiles[i] == null || recentFiles[i].length()==0) break;
	            	buttonRecent[i] = new JMenuItem((1+i) + " "+recentFiles[i],KeyEvent.VK_1+i);
	            	if(buttonRecent[i]!=null) {
	            		buttonRecent[i].addActionListener(this);
	            		subMenu.add(buttonRecent[i]);
	            	}
	        	}
	        }
        
        menu.add(subMenu);

        menu.addSeparator();
*/
        buttonHilbertCurve = new JButton(translator.get("MenuHilbertCurve"));
        buttonHilbertCurve.addActionListener(this);
        driver.add(buttonHilbertCurve);
        
        buttonText2GCODE = new JButton(translator.get("MenuTextToGCODE"));
        buttonText2GCODE.addActionListener(this);
        driver.add(buttonText2GCODE);

        buttonSaveFile = new JButton(translator.get("MenuSaveGCODEAs"));
        buttonSaveFile.addActionListener(this);
        driver.add(buttonSaveFile);

        return driver;
	}
	
	protected void JogMotors() {
		JDialog driver = new JDialog(mainframe,translator.get("JogMotors"),true);
		driver.setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		
		final JButton buttonAneg = new JButton(translator.get("JogIn"));
		final JButton buttonApos = new JButton(translator.get("JogOut"));
		final JCheckBox m1i = new JCheckBox(translator.get("Invert"),machineConfiguration.m1invert);
		
		final JButton buttonBneg = new JButton(translator.get("JogIn"));
		final JButton buttonBpos = new JButton(translator.get("JogOut"));
		final JCheckBox m2i = new JCheckBox(translator.get("Invert"),machineConfiguration.m2invert);

		c.gridx=0;	c.gridy=0;	driver.add(new JLabel(translator.get("Left")),c);
		c.gridx=0;	c.gridy=1;	driver.add(new JLabel(translator.get("Right")),c);
		
		c.gridx=1;	c.gridy=0;	driver.add(buttonAneg,c);
		c.gridx=1;	c.gridy=1;	driver.add(buttonBneg,c);
		
		c.gridx=2;	c.gridy=0;	driver.add(buttonApos,c);
		c.gridx=2;	c.gridy=1;	driver.add(buttonBpos,c);

		c.gridx=3;	c.gridy=0;	driver.add(m1i,c);
		c.gridx=3;	c.gridy=1;	driver.add(m2i,c);
		
		ActionListener driveButtons = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Object subject = e.getSource();
				if(subject == buttonApos) sendLineToRobot("D00 L100");
				if(subject == buttonAneg) sendLineToRobot("D00 L-100");
				if(subject == buttonBpos) sendLineToRobot("D00 R100");
				if(subject == buttonBneg) sendLineToRobot("D00 R-100");
				sendLineToRobot("M114");
			}
		};

		ActionListener invertButtons = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				machineConfiguration.m1invert = m1i.isSelected();
				machineConfiguration.m2invert = m2i.isSelected();
				machineConfiguration.SaveConfig();
				SendConfig();
			}
		};
		
		buttonApos.addActionListener(driveButtons);
		buttonAneg.addActionListener(driveButtons);
		
		buttonBpos.addActionListener(driveButtons);
		buttonBneg.addActionListener(driveButtons);
		
		m1i.addActionListener(invertButtons);
		m2i.addActionListener(invertButtons);

		sendLineToRobot("M114");
		driver.pack();
		driver.setVisible(true);
	}
	
	public JMenuBar CreateMenuBar() {
        // If the menu bar exists, empty it.  If it doesn't exist, create it.
        menuBar = new JMenuBar();

        updateMenuBar();
        
        return menuBar;
	}
	
	public void CheckForUpdate() {
		try {
		    // Get Github info
			URL github = new URL("https://www.marginallyclever.com/other/software-update-check.php?id=1");
	        BufferedReader in = new BufferedReader(new InputStreamReader(github.openStream()));

	        String inputLine;
	        if((inputLine = in.readLine()) != null) {
	        	if( inputLine.compareTo(version) !=0 ) {
	        		JOptionPane.showMessageDialog(null,translator.get("UpdateNotice"));
	        	} else {
	        		JOptionPane.showMessageDialog(null,translator.get("UpToDate"));
	        	}
	        } else {
	        	throw new Exception();
	        }
	        in.close();
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null,translator.get("UpdateCheckFailed"));
		}
	}

	// Rebuild the contents of the menu based on current program state
	public void updateMenuBar() {
		JMenu menu, subMenu;
		ButtonGroup group;
        int i;
        
        if(settingsPane!=null) {
            buttonAdjustMachineSize.setEnabled(!isrunning);
            buttonAdjustPulleySize.setEnabled(!isrunning);
            buttonJogMotors.setEnabled(connectionToRobot!=null && connectionToRobot.isRobotConfirmed() && !isrunning);
            buttonChangeTool.setEnabled(!isrunning);
            buttonAdjustTool.setEnabled(!isrunning);
        }
        if(preparePane!=null) {
            buttonHilbertCurve.setEnabled(!isrunning);
            buttonText2GCODE.setEnabled(!isrunning);
        }
        if(driveControls!=null) {
        	boolean x = connectionToRobot!=null && connectionToRobot.isRobotConfirmed();
        	driveControls.updateButtonAccess(x,isrunning);
        }
        
        
        menuBar.removeAll();
        
        
        // File menu
        menu = new JMenu(translator.get("MenuMakelangelo"));
        menu.setMnemonic(KeyEvent.VK_F);
        menuBar.add(menu);
        
        subMenu = new JMenu(translator.get("MenuPreferences"));
        
        buttonAdjustSounds = new JMenuItem(translator.get("MenuSoundsTitle"));
        buttonAdjustSounds.addActionListener(this);
        subMenu.add(buttonAdjustSounds);

        buttonAdjustGraphics = new JMenuItem(translator.get("MenuGraphicsTitle"));
        buttonAdjustGraphics.addActionListener(this);
        subMenu.add(buttonAdjustGraphics);

        buttonAdjustLanguage = new JMenuItem(translator.get("MenuLanguageTitle"));
        buttonAdjustLanguage.addActionListener(this);
        subMenu.add(buttonAdjustLanguage);
        menu.add(subMenu);
        
        buttonCheckForUpdate = new JMenuItem(translator.get("MenuUpdate"),KeyEvent.VK_U);
        buttonCheckForUpdate.addActionListener(this);
        buttonCheckForUpdate.setEnabled(true);
        menu.add(buttonCheckForUpdate);
        
        buttonAbout = new JMenuItem(translator.get("MenuAbout"),KeyEvent.VK_A);
        buttonAbout.addActionListener(this);
        menu.add(buttonAbout);

        menu.addSeparator();
        
        buttonExit = new JMenuItem(translator.get("MenuQuit"),KeyEvent.VK_Q);
        buttonExit.addActionListener(this);
        menu.add(buttonExit);
        
        
        // Connect menu
        subMenu = new JMenu(translator.get("MenuConnect"));
        subMenu.setEnabled(!isrunning);
        group = new ButtonGroup();

        String [] connections = connectionManager.listConnections();
        buttonPorts = new JRadioButtonMenuItem[connections.length];
        for(i=0;i<connections.length;++i) {
        	buttonPorts[i] = new JRadioButtonMenuItem(connections[i]);
            if(connectionToRobot!=null && connectionToRobot.getRecentConnection().equals(connections[i]) && connectionToRobot.isConnectionOpen()) {
            	buttonPorts[i].setSelected(true);
            }
            buttonPorts[i].addActionListener(this);
            group.add(buttonPorts[i]);
            subMenu.add(buttonPorts[i]);
        }
        
        subMenu.addSeparator();

        buttonRescan = new JMenuItem(translator.get("MenuRescan"),KeyEvent.VK_N);
        buttonRescan.addActionListener(this);
        subMenu.add(buttonRescan);

        buttonDisconnect = new JMenuItem(translator.get("MenuDisconnect"),KeyEvent.VK_D);
        buttonDisconnect.addActionListener(this);
        buttonDisconnect.setEnabled(connectionToRobot!=null && connectionToRobot.isConnectionOpen());
        subMenu.add(buttonDisconnect);
        
        menuBar.add(subMenu);
        
        // view menu
        menu = new JMenu(translator.get("MenuPreview"));
        buttonZoomOut = new JMenuItem(translator.get("ZoomOut"));
        buttonZoomOut.addActionListener(this);
        buttonZoomOut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,ActionEvent.ALT_MASK));
        menu.add(buttonZoomOut);
        
        buttonZoomIn = new JMenuItem(translator.get("ZoomIn"),KeyEvent.VK_EQUALS);
        buttonZoomIn.addActionListener(this);
        buttonZoomIn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,ActionEvent.ALT_MASK));
        menu.add(buttonZoomIn);
        
        buttonZoomToFit = new JMenuItem(translator.get("ZoomFit"));
        buttonZoomToFit.addActionListener(this);
        menu.add(buttonZoomToFit);
        
        menuBar.add(menu);

        // finish
        menuBar.updateUI();
    }

	// manages the vertical split in the GUI
	public class Splitter extends JSplitPane {
		static final long serialVersionUID=1;
		
		public Splitter(int split_direction) {
			super(split_direction);
			setResizeWeight(0.9);
			setDividerLocation(0.9);
		}
	}
	
    public Container CreateContentPane() {
        //Create the content-pane-to-be.
        JPanel contentPane = new JPanel(new BorderLayout());
        contentPane.setOpaque(true);
        
        // the log panel
        log = new JTextPane();
        log.setEditable(false);
        log.setBackground(Color.BLACK);
        logPane = new JScrollPane(log);
        kit = new HTMLEditorKit();
        doc = new HTMLDocument();
        log.setEditorKit(kit);
        log.setDocument(doc);
        DefaultCaret c = (DefaultCaret)log.getCaret();
        c.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        ClearLog();

        settingsPane = SettingsPanel();
        previewPane = new DrawPanel(machineConfiguration);
        preparePane = ProcessImages();
		driveControls = new MakelangeloDriveControls();
		driveControls.createPanel(this,translator,machineConfiguration);
		driveControls.updateButtonAccess(false, false);
        statusBar = new StatusBar(translator);

        contextMenu = new JTabbedPane();
        contextMenu.addTab(translator.get("MenuSettings"),null,settingsPane,null);
        contextMenu.addTab(translator.get("MenuGCODE"),null,preparePane,null);
        contextMenu.addTab(translator.get("MenuDraw"),null,driveControls,null);
        contextMenu.addTab("Log",null,logPane,null);

        // major layout
        split_left_right = new Splitter(JSplitPane.HORIZONTAL_SPLIT);
        split_left_right.add(previewPane);
        split_left_right.add(contextMenu);

        contentPane.add(statusBar,BorderLayout.SOUTH);
        contentPane.add(split_left_right,BorderLayout.CENTER);
		
        return contentPane;
    }
    
    // if the default file being opened in a g-code file, this is ok.  Otherwise it may take too long and look like a crash/hang.
    private void reopenLastFile() {
    	String s = recentFiles.get(0);
		if(s.length()>0) {
			OpenFileOnDemand(s);
		}
    }

    //private void TabToSettings() {
    //	contextMenu.setSelectedIndex(0);
    //}
    //private void TabToGcode() {
    //	contextMenu.setSelectedIndex(1);
    //}
    private void TabToDraw() {
    	contextMenu.setSelectedIndex(2);
    }
    private void TabToLog() {
    	contextMenu.setSelectedIndex(3);
    }

    public JFrame getParentFrame() {
    	return mainframe;
    }
    
    
    // Create the GUI and show it.  For thread safety, this method should be invoked from the event-dispatching thread.
    private void CreateAndShowGUI() {
        // Create and set up the window.
    	mainframe = new JFrame("Makelangelo");
        mainframe.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Create and set up the content pane.
        mainframe.setJMenuBar(CreateMenuBar());
        mainframe.setContentPane(CreateContentPane());
 
        // Display the window.
        int width =prefs.getInt("Default window width", 1200);
        int height=prefs.getInt("Default window height", 700);
        mainframe.setSize(width,height);
        mainframe.setVisible(true);
        
        previewPane.ZoomToFitPaper();
        
        // 2015-05-03: option is meaningless, connectionToRobot doesn't exist when software starts.
        // if(prefs.getBoolean("Reconnect to last port on start", false)) connectionToRobot.reconnect();
        if(prefs.getBoolean("Open last file on start", false)) reopenLastFile();
        if(prefs.getBoolean("Check for updates", false)) CheckForUpdate();
    }

	/**
	 *
	 * @return the <code>javax.swing.JFrame</code> representing the main frame of this GUI.
	 */
	public JFrame getMainframe() {
		return mainframe;
	}

	/**
	 *
	 * @return the <code>com.marginallyclever.makelangelo.DrawPanel</code> representing the preview pane of this GUI.
	 */
	public DrawPanel getPreviewPane() {
		return previewPane;
	}

	/**
	 *
	 * @param driveControls the <code>javax.swing.JPanel</code> representing the preview pane of this GUI.
	 */
	public void updatedriveControls() {
		driveControls.createPanel(this, translator, machineConfiguration);
	}

	/**
	 *
	 * @return the <code>GCodeFile</code> representing the G-Code file used by this GUI.
	 */
	public GCodeFile getGcodeFile() {
		return gcode;
	}
}


/**
 * This file is part of DrawbotGUI.
 *
 * DrawbotGUI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * DrawbotGUI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */
