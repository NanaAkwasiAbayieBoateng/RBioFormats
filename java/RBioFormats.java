import java.io.IOException;

import loci.common.DebugTools;
import loci.common.DataTools;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.ChannelFiller;
import loci.formats.ChannelSeparator;
import loci.formats.DimensionSwapper;
import loci.formats.FormatTools;
import loci.formats.services.OMEXMLService;
import loci.formats.services.OMEXMLServiceImpl;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.MissingLibraryException;
import loci.formats.meta.DummyMetadata;
import loci.formats.meta.MetadataStore;
import loci.formats.meta.IMetadata;

public final class RBioFormats {
  private static DimensionSwapper reader;
  private static MetadataStore omexml;
  
  static {
    // reduce verbosity
    DebugTools.enableLogging("ERROR");
  }
  
  public static IFormatReader getReader() {
    if (reader==null) {
      // ChannelFiller: convert indexed color images to RGB images.  
      // ChannelSeparator: split RGB images into 3 separate grayscale images
      // DimensionSwapper: enable setting output dimension order
      reader = new DimensionSwapper(new ChannelSeparator(new ChannelFiller()));
    }
    return reader;
  }
  
  public static MetadataStore getOMEXML() {
    return omexml;
  }
  
  public static String getCurrentFile() {
    return reader.getCurrentFile();
  }
  
  // setup the reader
  public static void setupReader(String file, boolean filter, boolean proprietary, boolean xml) throws FormatException, IOException {
  
    // set metadata options
    reader.setMetadataFiltered(filter);
    reader.setOriginalMetadataPopulated(proprietary);
    reader.setFlattenedResolutions(false);
    
    // omexml
    if (xml) {
      omexml = (MetadataStore) getMetadataStore();
    }
    else {
      omexml = new DummyMetadata();
    }
    reader.setMetadataStore(omexml);
    
    // initialize file   
    reader.setId(file);
    reader.setOutputOrder("XYCZT");
  }
  
  public static Object readPixels(int i, int x, int y, int w, int h, boolean normalize) throws FormatException, IOException {
    int pixelType = reader.getPixelType();
    int size = w * h * FormatTools.getBytesPerPixel(pixelType) * reader.getRGBChannelCount();
    
    byte[] buf = new byte[size];
    
    reader.openBytes(i, buf, x, y, w, h);
    
    int bpp = FormatTools.getBytesPerPixel(pixelType);
    boolean fp = FormatTools.isFloatingPoint(pixelType);
    boolean little = reader.isLittleEndian();
    
    if (normalize)
      return normalizedDataArray(buf, bpp, fp, little, pixelType);
    else
      return rawDataArray(buf, bpp, FormatTools.isSigned(pixelType), fp, little);
  }
  
  private static IMetadata getMetadataStore() throws FormatException {
    try {
      ServiceFactory factory = new ServiceFactory();
      OMEXMLService service = factory.getInstance(OMEXMLService.class);
      return service.createOMEXMLMetadata();
    }
    catch (DependencyException de) {
      throw new MissingLibraryException(OMEXMLServiceImpl.NO_OME_XML_MSG, de);
    }
    catch (ServiceException se) {
      throw new FormatException(se);
    }
  }
  
  private static Object rawDataArray(byte[] b, int bpp, boolean signed, boolean fp, boolean little) {
    // unsigned types need to be stored in a longer signed type
    int type = signed ? bpp : bpp * 2;
    
    // int8
    if (type == 1) {
      return b;
    }
    // uint8, int16
    else if (type == 2) {
      short[] s = new short[b.length / bpp];
      for (int i=0; i<s.length; i++) {
        s[i] = DataTools.bytesToShort(b, i * bpp, bpp, little);
      }
      return s;
    }
    // float
    else if (type == 4 && fp) {
      float[] f = new float[b.length / bpp];
      for (int i=0; i<f.length; i++) {
        f[i] = DataTools.bytesToFloat(b, i * bpp, bpp, little);
      }
      return f;
    }
    // uint16
    else if (type == 4 && !signed) {
      int[] i = new int[b.length / bpp];
      for (int j=0; j<i.length; j++) {
        i[j] = DataTools.bytesToInt(b, j * bpp, bpp, little);
      }
      return i;
    }
    // int32
    // we cannot use 32bit ints for signed values because
    // the minimal int value in Java -2^31 = -2147483648 represents NA in R
    // https://github.com/s-u/rJava/issues/39#issuecomment-72207912
    else if (type == 4) {
      double[] d = new double[b.length / bpp];
      for (int j=0; j<d.length; j++) {
        d[j] = (double) DataTools.bytesToInt(b, j * bpp, bpp, little);
      }
      return d;
    }
    // double
    else if (type == 8 && fp) {
      double[] d = new double[b.length / bpp];
      for (int i=0; i<d.length; i++) {
        d[i] = DataTools.bytesToDouble(b, i * bpp, bpp, little);
      }
      return d;
    }
    // uint32
    // use Java long which is returned as double in R
    else if (type == 8) {
      long[] l = new long[b.length / bpp];
      for (int i=0; i<l.length; i++) {
        l[i] = DataTools.bytesToLong(b, i * bpp, bpp, little);
      }
      return l;
    }
    return null;
  }

  private static double[] normalizedDataArray(byte[] b, int bpp, boolean fp, boolean little, int pixelType) {
    double[] data = new double[b.length / bpp];
    
    // floating point normalization 
    if (fp) {
      double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
      
      if (bpp == 4) {
        for (int i=0; i<data.length; i++) {
          data[i] = (double) DataTools.bytesToFloat(b, i * bpp, bpp, little);
          if (data[i] == Double.POSITIVE_INFINITY || data[i] == Double.NEGATIVE_INFINITY) 
            continue;
          else {
            if (data[i] < min) min = data[i];
            if (data[i] > max) max = data[i];
          }
        }
      }
      else if (bpp == 8) {
        for (int i=0; i<data.length; i++) {
          data[i] = DataTools.bytesToDouble(b, i * bpp, bpp, little);
          if (data[i] == Double.POSITIVE_INFINITY || data[i] == Double.NEGATIVE_INFINITY) 
            continue;
          else {
            if (data[i] < min) min = data[i];
            if (data[i] > max) max = data[i];
          }
        }      
      }
      else return null;
      
      // normalize min => 0.0, max => 1.0
      double range = max - min;
      for (int i=0; i<data.length; i++) {
        if (data[i] == Double.POSITIVE_INFINITY) data[i] = 1.0;
        else if (data[i] == Double.NEGATIVE_INFINITY) data[i] = 0.0;
        else data[i] = (data[i] - min) / range;
      }
    }
    else {
      long[] minmax = FormatTools.defaultMinMax(pixelType);
      double min = minmax[0], max = minmax[1];
       
      // true bpp value overrides the default
      int bitsPerPixel = reader.getCoreMetadataList().get(reader.getCoreIndex()).bitsPerPixel;
      if ( !FormatTools.isSigned(pixelType) &&  bitsPerPixel < FormatTools.getBytesPerPixel(pixelType) * 8) max = Math.pow(2, bitsPerPixel) - 1;	
      
      double range = max - min;
      for(int i = 0; i < data.length; ++i) data[i] = ((double) DataTools.bytesToLong(b, i * bpp, bpp, little) - min) / range;      
    }
    
    return data;
  }
  
}
