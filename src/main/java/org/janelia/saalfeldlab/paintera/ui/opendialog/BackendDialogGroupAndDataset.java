package org.janelia.saalfeldlab.paintera.ui.opendialog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.paintera.state.FragmentSegmentAssignmentOnlyLocal;
import org.janelia.saalfeldlab.paintera.state.FragmentSegmentAssignmentState;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.effect.Effect;
import javafx.scene.effect.InnerShadow;
import javafx.scene.paint.Color;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.view.Views;

public abstract class BackendDialogGroupAndDataset implements SourceFromRAI, CombinesErrorMessages
{

	protected final SimpleObjectProperty< String > groupProperty = new SimpleObjectProperty<>();

	protected final SimpleObjectProperty< String > dataset = new SimpleObjectProperty<>();

	protected final SimpleObjectProperty< String > groupError = new SimpleObjectProperty<>();

	protected final SimpleObjectProperty< Effect > groupErrorEffect = new SimpleObjectProperty<>();

	protected final SimpleObjectProperty< String > datasetError = new SimpleObjectProperty<>();

	protected final SimpleObjectProperty< String > error = new SimpleObjectProperty<>();

	protected final DatasetInfo datasetInfo = new DatasetInfo();

	protected final ExecutorService singleThreadExecutorService = Executors.newFixedThreadPool( 1, r -> {
		final Thread thread = Executors.defaultThreadFactory().newThread( r );
		thread.setName( BackendDialogGroupAndDataset.class.getSimpleName() + "-single-thread-pool" );
		return thread;
	} );

	protected final ArrayList< Future< Void > > directoryTraversalTasks = new ArrayList<>();

	protected final SimpleBooleanProperty isTraversingDirectories = new SimpleBooleanProperty();

	protected final BooleanBinding isValid = Bindings.createBooleanBinding( () -> Optional.ofNullable( groupError.get() ).orElse( "" ).length() == 0, groupError );

	protected final StringBinding traversalMessage =
			Bindings.createStringBinding( () -> isTraversingDirectories.get() ? "Discovering datasets" : "", isTraversingDirectories );

	protected final Effect textFieldNoErrorEffect = new TextField().getEffect();

	protected final Effect textFieldErrorEffect = new InnerShadow( 10, Color.ORANGE );

	protected final ObservableList< String > datasetChoices = FXCollections.observableArrayList();

	protected final GroupAndDatasetStructure nodeCreator;

	protected BackendDialogGroupAndDataset( final String groupPrompt, final String datasetPrompt, final BiFunction< String, Scene, String > onBrowseClicked )
	{
		this.nodeCreator = new GroupAndDatasetStructure(
				groupPrompt,
				datasetPrompt,
				groupProperty,
				dataset,
				datasetChoices,
				this.isTraversingDirectories.or( this.isValid.not() ),
				onBrowseClicked );
		groupProperty.addListener( ( obs, oldv, newv ) -> {
			if ( newv != null && !newv.equals( oldv ) && new File( newv ).exists() )
			{
				this.groupError.set( null );
				synchronized ( directoryTraversalTasks )
				{
					directoryTraversalTasks.forEach( f -> f.cancel( true ) );
					directoryTraversalTasks.add( singleThreadExecutorService.submit( () -> {
						this.isTraversingDirectories.set( true );
						try
						{
							final List< String > files = discoverDatasetAt( newv );
							if ( !Thread.currentThread().isInterrupted() )
							{
								InvokeOnJavaFXApplicationThread.invoke( () -> datasetChoices.setAll( files ) );
								if ( !oldv.equals( newv ) )
								{
									InvokeOnJavaFXApplicationThread.invoke( () -> this.dataset.set( null ) );
								}
							}
						}
						finally
						{
							this.isTraversingDirectories.set( false );
						}
						return null;
					} ) );
				}
			}
			else
			{
				datasetChoices.clear();
				this.groupError.set( "Not a valid group" );
			}
		} );
		dataset.addListener( ( obs, oldv, newv ) -> {
			if ( newv != null && newv.length() > 0 )
			{
				datasetError.set( null );
				updateDatasetInfo( newv, this.datasetInfo );
			}
			else
			{
				datasetError.set( "No dataset selected" );
			}
		} );

		groupError.addListener( ( obs, oldv, newv ) -> this.groupErrorEffect.set( newv != null && newv.length() > 0 ? textFieldErrorEffect : textFieldNoErrorEffect ) );

		this.isValid.addListener( ( obs, oldv, newv ) -> {
			synchronized ( directoryTraversalTasks )
			{
				directoryTraversalTasks.forEach( task -> task.cancel( !newv ) );
				directoryTraversalTasks.clear();
			}
		} );

		this.errorMessages().forEach( em -> em.addListener( ( obs, oldv, newv ) -> combineErrorMessages() ) );

		groupProperty.set( "" );
		dataset.set( "" );
	}

	public abstract void updateDatasetInfo( String dataset, DatasetInfo info );

	public abstract List< String > discoverDatasetAt( String at );

	@Override
	public Node getDialogNode()
	{
		return nodeCreator.createNode();
	}

	@Override
	public ObjectProperty< String > errorMessage()
	{
		return error;
	}

	@Override
	public DoubleProperty[] resolution()
	{
		return this.datasetInfo.spatialResolutionProperties();
	}

	@Override
	public DoubleProperty[] offset()
	{
		return this.datasetInfo.spatialOffsetProperties();
	}

	@Override
	public DoubleProperty min()
	{
		return this.datasetInfo.minProperty();
	}

	@Override
	public DoubleProperty max()
	{
		return this.datasetInfo.maxProperty();
	}

	@Override
	public Collection< ObservableValue< String > > errorMessages()
	{
		return Arrays.asList( this.groupError, this.traversalMessage, this.datasetError );
	}

	@Override
	public Consumer< Collection< String > > combiner()
	{
		return strings -> this.error.set( String.join( "\n", strings ) );
	}

	@Override
	public Iterator< ? extends FragmentSegmentAssignmentState< ? > > assignments()
	{
		final String root = groupProperty.get();
		final String dataset = this.dataset.get() + ".fragment-segment-assignment";

		try
		{
			final N5Writer writer = N5Helpers.n5Writer( root, 2, 1 );

			final BiConsumer< long[], long[] > persister = ( keys, values ) -> {
				if ( keys.length == 0 )
				{
					LOG.warn( "Zero length data, will not persist fragment-segment-assignment." );
					return;
				}
				try
				{
					final DatasetAttributes attrs = new DatasetAttributes( new long[] { 2, keys.length }, new int[] { 1, keys.length }, DataType.UINT64, new GzipCompression() );
					writer.createDataset( dataset, attrs );
					final DataBlock< long[] > keyBlock = new LongArrayDataBlock( new int[] { 1, keys.length }, new long[] { 0, 0 }, keys );
					final DataBlock< long[] > valueBlock = new LongArrayDataBlock( new int[] { 1, values.length }, new long[] { 1, 0 }, values );
					writer.writeBlock( dataset, attrs, keyBlock );
					writer.writeBlock( dataset, attrs, valueBlock );
				}
				catch ( final IOException e )
				{
					throw new RuntimeException( e );
				}
			};

			final long[] keys;
			final long[] values;
			if ( writer.datasetExists( dataset ) )
			{
				final DatasetAttributes attrs = writer.getDatasetAttributes( dataset );
				final int numEntries = ( int ) attrs.getDimensions()[ 1 ];
				keys = new long[ numEntries ];
				values = new long[ numEntries ];
				final RandomAccessibleInterval< UnsignedLongType > data = N5Utils.open( writer, dataset );

				final Cursor< UnsignedLongType > keysCursor = Views.flatIterable( Views.hyperSlice( data, 0, 0l ) ).cursor();
				for ( int i = 0; keysCursor.hasNext(); ++i )
				{
					keys[ i ] = keysCursor.next().get();
				}

				final Cursor< UnsignedLongType > valuesCursor = Views.flatIterable( Views.hyperSlice( data, 0, 1l ) ).cursor();
				for ( int i = 0; valuesCursor.hasNext(); ++i )
				{
					values[ i ] = valuesCursor.next().get();
				}
			}
			else
			{
				keys = new long[] {};
				values = new long[] {};
			}

			final FragmentSegmentAssignmentOnlyLocal assignment = new FragmentSegmentAssignmentOnlyLocal( keys, values, persister );

			return Stream.generate( () -> assignment ).iterator();
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( e );
		}
	}

}