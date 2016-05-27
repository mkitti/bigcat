package bdv.bigcat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO;

import bdv.BigDataViewer;
import bdv.bigcat.annotation.AnnotationsHdf5Store;
import bdv.bigcat.composite.ARGBCompositeAlphaYCbCr;
import bdv.bigcat.composite.Composite;
import bdv.bigcat.composite.CompositeCopy;
import bdv.bigcat.control.AnnotationsController;
import bdv.bigcat.control.LabelBrushController;
import bdv.bigcat.control.LabelFillController;
import bdv.bigcat.control.LabelPersistenceController;
import bdv.bigcat.control.LabelRestrictToSegmentController;
import bdv.bigcat.control.MergeController;
import bdv.bigcat.control.SelectionController;
import bdv.bigcat.control.TranslateZController;
import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.bigcat.label.PairLabelMultiSetLongIdPicker;
import bdv.bigcat.ui.ARGBConvertedLabelPairSource;
import bdv.bigcat.ui.GoldenAngleSaturatedARGBStream;
import bdv.bigcat.ui.Util;
import bdv.img.SetCache;
import bdv.img.h5.AbstractH5SetupImageLoader;
import bdv.img.h5.H5LabelMultisetSetupImageLoader;
import bdv.img.h5.H5UnsignedByteSetupImageLoader;
import bdv.img.h5.H5Utils;
import bdv.img.labelpair.RandomAccessiblePair;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultisetType;
import bdv.labels.labelset.Multiset;
import bdv.labels.labelset.VolatileLabelMultisetType;
import bdv.util.IdService;
import bdv.util.LocalIdService;
import bdv.viewer.TriggerBehaviourBindings;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import gnu.trove.map.hash.TLongLongHashMap;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class BigCat
{
	final static private int[] cellDimensions = new int[]{ 64, 64, 8 };

	private H5LabelMultisetSetupImageLoader fragments = null;
	private ARGBConvertedLabelPairSource convertedLabelPair = null;
	private CellImg< LongType, ?, ? > paintedLabels = null;
	private BigDataViewer bdv;
	private GoldenAngleSaturatedARGBStream colorStream;
	private FragmentSegmentAssignment assignment;
	private String projectFile;
	private String paintedLabelsDataset;
	private String mergedLabelsDataset;
	private String fragmentSegmentLutDataset;
	private InputTriggerConfig config;

	private IdService idService = new LocalIdService();

	public static void main( final String[] args ) throws Exception
	{
		final BigCat bca = new BigCat(args);
	}

	public BigCat( final String[] args ) throws Exception
	{
		Util.initUI();

		this.config = getInputTriggerConfig();

		projectFile = args[ 0 ];

		String labelsDataset = "neuron_ids";
		if ( args.length > 1 )
			labelsDataset = args[ 1 ];

		String rawDataset = "raw";
		if ( args.length > 2 )
			rawDataset = args[ 2 ];

		fragmentSegmentLutDataset = "/fragment_segment_lut";
		if ( args.length > 3 )
			fragmentSegmentLutDataset = args[ 3 ];

		System.out.println( "Opening " + projectFile );
		final IHDF5Reader reader = HDF5Factory.open( projectFile );

		// support both file_format 0.0 and >=0.1
		final String volumesPath = reader.isGroup( "/volumes" ) ? "/volumes" : "";
		final String labelsPath = reader.isGroup( volumesPath + "/labels" ) ? volumesPath + "/labels" : "";

		/* raw pixels */
		final String rawPath = volumesPath + "/" + rawDataset;
		final H5UnsignedByteSetupImageLoader raw = new H5UnsignedByteSetupImageLoader( reader, rawPath, 0, cellDimensions );

		/* fragments */
		String fragmentsPath = labelsPath + "/" + labelsDataset;
		mergedLabelsDataset = labelsPath + "/merged_" + labelsDataset;
		paintedLabelsDataset = labelsPath + "/painted_" + labelsDataset;
		fragmentsPath = reader.object().isDataSet( mergedLabelsDataset ) ? mergedLabelsDataset : fragmentsPath;
		if ( reader.exists( fragmentsPath ) )
			readFragments( args, reader, fragmentsPath, paintedLabelsDataset, fragmentSegmentLutDataset );
		else
			System.out.println( "no labels found cooresponding to requested dataset '" + labelsDataset + "' (searched in '" + labelsPath + "')" );

		setupBdv( raw );
	}

	private void readFragments(
			final String[] args,
			final IHDF5Reader reader,
			final String labelsDataset,
			final String paintedLabelsDataset,
			final String fragmentSegmentLutDataset ) throws IOException
	{
		fragments =
				new H5LabelMultisetSetupImageLoader(
						reader,
						null,
						labelsDataset,
						1,
						cellDimensions );
		final RandomAccessibleInterval< VolatileLabelMultisetType > fragmentsPixels = fragments.getVolatileImage( 0, 0 );
		final long[] fragmentsDimensions = Intervals.dimensionsAsLongArray( fragmentsPixels );

		final Long nextIdObject = H5Utils.loadAttribute( reader, "/", "next_id" );
		long maxId = 0;
		if ( nextIdObject == null )
		{
			maxId = maxId( reader, labelsDataset, maxId );
			if ( reader.exists( paintedLabelsDataset ) )
				maxId = maxId( reader, paintedLabelsDataset, maxId );
		}
		else
			maxId = nextIdObject.longValue() - 1;

		idService.invalidate( maxId );

		final String paintedLabelsFilePath = args[ 0 ];
		final File paintedLabelsFile = new File( paintedLabelsFilePath );
		if ( paintedLabelsFile.exists() && reader.exists( paintedLabelsDataset ) )
				paintedLabels = H5Utils.loadUnsignedLong( new File( paintedLabelsFilePath ), paintedLabelsDataset, cellDimensions );
		else
		{
			paintedLabels = new CellImgFactory< LongType >( cellDimensions ).create( fragmentsDimensions, new LongType() );
			for ( final LongType t : paintedLabels )
				t.set( Label.TRANSPARENT );
		}

		/* pair labels */
		final RandomAccessiblePair< VolatileLabelMultisetType, LongType > labelPair =
				new RandomAccessiblePair<>(
						fragments.getVolatileImage( 0, 0 ),
						paintedLabels );

		assignment = new FragmentSegmentAssignment( idService );
		final TLongLongHashMap lut = H5Utils.loadLongLongLut( reader, fragmentSegmentLutDataset, 1024 );
		if ( lut != null )
			assignment.initLut( lut );

		colorStream = new GoldenAngleSaturatedARGBStream( assignment );
		colorStream.setAlpha( 0x20 );
		convertedLabelPair =
				new ARGBConvertedLabelPairSource(
						3,
						labelPair,
						paintedLabels, // as Interval, used just for the size
						fragments.getMipmapTransforms(),
						colorStream );
	}

	@SuppressWarnings( "unchecked" )
	private void setupBdv( final H5UnsignedByteSetupImageLoader raw ) throws Exception
	{
		/* composites */
		final ArrayList< Composite< ARGBType, ARGBType > > composites = new ArrayList< Composite< ARGBType, ARGBType > >();
		composites.add( new CompositeCopy< ARGBType >() );

		final String windowTitle = "BigCAT";

		if ( fragments != null )
		{
			composites.add( new ARGBCompositeAlphaYCbCr() );
			bdv = Util.createViewer(
				windowTitle,
				new AbstractH5SetupImageLoader[]{ raw },
				new ARGBConvertedLabelPairSource[]{ convertedLabelPair },
				new SetCache[]{ fragments },
				composites,
				config );
		}
		else
		{
			bdv = Util.createViewer(
				windowTitle,
				new AbstractH5SetupImageLoader[]{ raw },
				new ARGBConvertedLabelPairSource[]{},
				new SetCache[]{},
				composites,
				config );
		}

		bdv.getViewerFrame().setVisible( true );

		final TriggerBehaviourBindings bindings = bdv.getViewerFrame().getTriggerbindings();

		if ( fragments != null )
		{
			final PairLabelMultiSetLongIdPicker idPicker2 = new PairLabelMultiSetLongIdPicker(
					bdv.getViewer(),
					RealViews.affineReal(
							Views.interpolate(
									new RandomAccessiblePair< LabelMultisetType, LongType >(
											Views.extendValue(
												fragments.getImage( 0 ),
												new LabelMultisetType() ),
											Views.extendValue(
													paintedLabels,
													new LongType( Label.TRANSPARENT ) ) ),
									new NearestNeighborInterpolatorFactory< Pair< LabelMultisetType, LongType > >() ),
							fragments.getMipmapTransforms()[ 0 ] )
					);

			final SelectionController selectionController = new SelectionController(
					bdv.getViewer(),
					colorStream,
					idService,
					config,
					bdv.getViewerFrame().getKeybindings(),
					config);

			final MergeController mergeController = new MergeController(
					bdv.getViewer(),
					idPicker2,
					selectionController,
					assignment,
					config,
					bdv.getViewerFrame().getKeybindings(),
					config);

			final LabelBrushController brushController = new LabelBrushController(
					bdv.getViewer(),
					paintedLabels,
					fragments.getMipmapTransforms()[ 0 ],
					assignment,
					selectionController,
					projectFile,
					paintedLabelsDataset,
					cellDimensions,
					config);

			final LabelPersistenceController persistenceController = new LabelPersistenceController(
					bdv.getViewer(),
					fragments.getImage( 0 ),
					paintedLabels,
					assignment,
					idService,
					projectFile,
					paintedLabelsDataset,
					mergedLabelsDataset,
					cellDimensions,
					fragmentSegmentLutDataset,
					config,
					bdv.getViewerFrame().getKeybindings() );

			final LabelFillController fillController = new LabelFillController(
					bdv.getViewer(),
					fragments.getImage( 0 ),
					paintedLabels,
					fragments.getMipmapTransforms()[ 0 ],
					assignment,
					selectionController,
					new DiamondShape( 1 ),
					config);

			final LabelRestrictToSegmentController intersectController = new LabelRestrictToSegmentController(
					bdv.getViewer(),
					fragments.getImage( 0 ),
					paintedLabels,
					fragments.getMipmapTransforms()[ 0 ],
					assignment,
					selectionController,
					new DiamondShape( 1 ),
					config );

			bindings.addBehaviourMap( "merge", mergeController.getBehaviourMap() );
			bindings.addInputTriggerMap( "merge", mergeController.getInputTriggerMap() );

			bindings.addBehaviourMap( "brush", brushController.getBehaviourMap() );
			bindings.addInputTriggerMap( "brush", brushController.getInputTriggerMap() );

			bindings.addBehaviourMap( "fill", fillController.getBehaviourMap() );
			bindings.addInputTriggerMap( "fill", fillController.getInputTriggerMap() );

			bindings.addBehaviourMap( "restrict", intersectController.getBehaviourMap() );
			bindings.addInputTriggerMap( "restrict", intersectController.getInputTriggerMap() );

			bdv.getViewer().getDisplay().addOverlayRenderer( brushController.getBrushOverlay() );

		}

		final TranslateZController translateZController = new TranslateZController(
				bdv.getViewer(),
				raw.getMipmapResolutions()[ 0 ],
				config );
		bindings.addBehaviourMap( "translate_z", translateZController.getBehaviourMap() );

		final AnnotationsHdf5Store annotationsStore = new AnnotationsHdf5Store( projectFile, idService );
		final AnnotationsController annotationController = new AnnotationsController(
				annotationsStore,
				bdv,
				idService,
				config,
				bdv.getViewerFrame().getKeybindings(),
				config );

		bindings.addBehaviourMap( "annotation", annotationController.getBehaviourMap() );
		bindings.addInputTriggerMap( "annotation", annotationController.getInputTriggerMap() );

		bdv.getViewer().getDisplay().addOverlayRenderer( annotationController.getAnnotationOverlay() );
	}

	protected InputTriggerConfig getInputTriggerConfig() throws IllegalArgumentException {

		final String[] filenames = {
				"bigcatkeyconfig.yaml",
				System.getProperty( "user.home" ) + "/.bdv/bigcatkeyconfig.yaml"
		};

		for (final String filename : filenames) {

			try {
				if (new File(filename).isFile()) {
					System.out.println("reading key config from file " + filename);
					return new InputTriggerConfig(YamlConfigIO.read(filename));
				}
			} catch (final IOException e) {
				System.err.println("Error reading " + filename);
			}
		}

		System.out.println("creating default input trigger config");

		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config = new InputTriggerConfig(
				Arrays.asList(
						new InputTriggerDescription[] {
								new InputTriggerDescription( new String[] { "not mapped" }, "drag rotate slow", "bdv" )
						}
				)
		);

		return config;
	}

	final static protected long maxId(
			final IHDF5Reader reader,
			final String labelsDataset,
			long maxId ) throws IOException
	{
		final H5LabelMultisetSetupImageLoader labelLoader =
				new H5LabelMultisetSetupImageLoader(
						reader,
						null,
						labelsDataset,
						1,
						cellDimensions );

		for ( final LabelMultisetType t : Views.iterable( labelLoader.getImage( 0 ) ) )
		{
			for ( final Multiset.Entry< Label > v : t.entrySet() )
			{
				final long id = v.getElement().id();
				if ( id != Label.TRANSPARENT && IdService.greaterThan( id, maxId ) )
					maxId = id;
			}
		}

		return maxId;
	}
}
