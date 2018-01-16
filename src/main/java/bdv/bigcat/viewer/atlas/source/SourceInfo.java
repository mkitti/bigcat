package bdv.bigcat.viewer.atlas.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.viewer.ToIdConverter;
import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.mode.Mode;
import bdv.bigcat.viewer.atlas.source.AtlasSourceState.LabelSourceState;
import bdv.bigcat.viewer.atlas.source.AtlasSourceState.RawSourceState;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentState;
import bdv.bigcat.viewer.state.SelectedIds;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableIntegerValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.paint.Color;
import net.imglib2.converter.Converter;
import net.imglib2.type.Type;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;

public class SourceInfo
{

	private final ObservableMap< Source< ? >, AtlasSourceState< ?, ? > > states = FXCollections.observableHashMap();

	private final ObservableList< Source< ? > > sources = FXCollections.observableArrayList();

	private final ObservableList< Source< ? > > sourcesReadOnly = FXCollections.unmodifiableObservableList( sources );

	private final ObjectProperty< Source< ? > > currentSource = new SimpleObjectProperty<>( null );

	private final ObservableList< Source< ? > > visibleSources = FXCollections.observableArrayList();

	private final ObservableList< Source< ? > > visibleSourcesReadOnly = FXCollections.unmodifiableObservableList( visibleSources );
	{
		sources.addListener( ( ListChangeListener< Source< ? > > ) change -> updateVisibleSources() );
	}

	private final ObservableList< SourceAndConverter< ? > > visibleSourcesAndConverter = FXCollections.observableArrayList();

	private final ObservableList< SourceAndConverter< ? > > visibleSourcesAndConverterReadOnly = FXCollections.unmodifiableObservableList( visibleSourcesAndConverter );
	{
		visibleSources.addListener( ( ListChangeListener< Source< ? > > ) change -> updateVisibleSourcesAndConverters() );
	}

	private final IntegerProperty currentSourceIndex = new SimpleIntegerProperty( -1 );
	{
		this.currentSource.addListener( ( oldv, obs, newv ) -> this.currentSourceIndex.set( this.sources.indexOf( newv ) ) );
		this.currentSourceIndex.addListener( ( oldv, obs, newv ) -> this.currentSource.set( newv.intValue() < 0 || newv.intValue() >= this.sources.size() ? null : this.sources.get( newv.intValue() ) ) );
	}

	private final IntegerProperty currentSourceIndexInVisibleSources = new SimpleIntegerProperty();
	{
		this.currentSource.addListener( ( oldv, obs, newv ) -> updateCurrentSourceIndexInVisibleSources() );
		this.visibleSources.addListener( ( ListChangeListener< Source< ? > > ) ( change ) -> updateCurrentSourceIndexInVisibleSources() );
		updateCurrentSourceIndexInVisibleSources();
	}

	public < D extends Type< D >, T extends RealType< T > > RawSourceState< T, D > addRawSource(
			final DataSource< D, T > source,
			final double min,
			final double max,
			final Color color )
	{
		final RawSourceState< T, D > state = new AtlasSourceState.RawSourceState<>( source, min, max );
		state.colorProperty().set( color );
		addState( source, state );
		return state;
	}

	public < D extends Type< D >, T extends Type< T >, F extends FragmentSegmentAssignmentState< F > > LabelSourceState< T, D > addLabelSource(
			final DataSource< D, T > source,
			final ToIdConverter idConverter,
			final Function< D, Converter< D, BoolType > > toBoolConverter,
			final F frag,
			final HashMap< Mode, ARGBStream > stream,
			final HashMap< Mode, SelectedIds > selectedId,
			final Converter< T, ARGBType > converter )
	{
		final LabelSourceState< T, D > state = new AtlasSourceState.LabelSourceState<>( source, converter );
		state.toIdConverterProperty().set( idConverter );
		state.maskGeneratorProperty().set( toBoolConverter );
		state.assignmentProperty().set( frag );
		state.streams().clear();
		state.streams().putAll( stream );
		state.selectedIds().clear();
		state.selectedIds().putAll( selectedId );
		addState( source, state );
		return state;
	}

	private synchronized < D extends Type< D >, T extends Type< T > > void addState( final Source< T > source, final AtlasSourceState< T, D > state )
	{
		this.states.put( source, state );
		this.sources.add( source );
		state.visibleProperty().addListener( ( obs, oldv, newv ) -> updateVisibleSources() );
		state.converterProperty().addListener( ( obs, oldv, newv ) -> updateVisibleSourcesAndConverters() );
		state.visibleProperty().set( true );
		if ( this.currentSource.get() == null )
			this.currentSource.set( source );
	}

	public synchronized < T > void removeSource( final Source< T > source )
	{
		final int currentSourceIndex = this.sources.indexOf( source );
		this.states.remove( source );
		this.sources.remove( source );
		this.currentSource.set( this.sources.size() == 0 ? null : this.sources.get( Math.max( currentSourceIndex - 1, 0 ) ) );
	}

	public synchronized void addMode( final Mode mode, final Function< Source< ? >, Optional< ARGBStream > > makeStream, final Function< Source< ? >, Optional< SelectedIds > > makeSelection )
	{
		for ( final Entry< Source< ? >, AtlasSourceState< ?, ? > > sourceAndState : states.entrySet() )
			if ( sourceAndState.getValue() instanceof LabelSourceState< ?, ? > )
			{
				final LabelSourceState< ?, ? > lstate = ( LabelSourceState< ?, ? > ) sourceAndState.getValue();
				makeStream.apply( sourceAndState.getKey() ).ifPresent( s -> lstate.streams().put( mode, s ) );
				makeSelection.apply( sourceAndState.getKey() ).ifPresent( s -> lstate.selectedIds().put( mode, s ) );
			}
	}

	public synchronized void removeMode( final Mode mode )
	{
		this.states.values().stream().filter( s -> s instanceof LabelSourceState< ?, ? > ).map( s -> ( LabelSourceState< ?, ? > ) s ).forEach( s -> s.streams().remove( mode ) );
		this.states.values().stream().filter( s -> s instanceof LabelSourceState< ?, ? > ).map( s -> ( LabelSourceState< ?, ? > ) s ).forEach( s -> s.selectedIds().remove( mode ) );
	}

	public synchronized Optional< ToIdConverter > toIdConverter( final Source< ? > source )
	{
		final AtlasSourceState< ?, ? > state = states.get( source );
		return state == null || !( state instanceof LabelSourceState< ?, ? > ) ? Optional.empty() : Optional.ofNullable( ( ( LabelSourceState< ?, ? > ) state ).toIdConverterProperty().get() );
	}

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public synchronized Optional< Function< ?, Converter< ?, BoolType > > > toBoolConverter( final Source< ? > source )
	{
		final AtlasSourceState< ?, ? > state = states.get( source );
		return state == null ? Optional.empty() : Optional.ofNullable( ( Function< ?, Converter< ?, BoolType > > ) ( Function ) state.maskGeneratorProperty().get() );
	}

	public synchronized Optional< ? extends FragmentSegmentAssignmentState< ? > > assignment( final Source< ? > source )
	{
		final AtlasSourceState< ?, ? > state = states.get( source );
		return state != null && state instanceof LabelSourceState< ?, ? > ? Optional.of( ( ( LabelSourceState< ?, ? > ) state ).assignmentProperty().get() ) : Optional.empty();
	}

	public synchronized Optional< ARGBStream > stream( final Source< ? > source, final Mode mode )
	{
		final AtlasSourceState< ?, ? > state = states.get( source );
		return state == null || !( state instanceof LabelSourceState< ?, ? > ) ? Optional.empty() : Optional.ofNullable( ( ( LabelSourceState< ?, ? > ) state ).streams().get( mode ) );
	}

	public synchronized Optional< SelectedIds > selectedIds( final Source< ? > source, final Mode mode )
	{
		final AtlasSourceState< ?, ? > state = states.get( source );
		return state == null || !( state instanceof LabelSourceState< ?, ? > ) ? Optional.empty() : Optional.ofNullable( ( ( LabelSourceState< ?, ? > ) state ).selectedIds().get( mode ) );
	}

	public synchronized void forEachStream( final Source< ? > source, final Consumer< ARGBStream > actor )
	{
		Optional
				.ofNullable( states.get( source ) )
				.filter( LabelSourceState.class::isInstance )
				.map( s -> ( ( LabelSourceState< ?, ? > ) s ).streams() )
				.map( Map::values ).orElseGet( () -> new ArrayList<>() ).stream().forEach( actor );
	}

	public int numSources()
	{
		return this.states.size();
	}

	public AtlasSourceState< ?, ? > getState( final Source< ? > source )
	{
		return states.get( source );
	}

	public ObservableList< Source< ? > > trackSources()
	{
		return this.sourcesReadOnly;
	}

	public ObservableList< Source< ? > > trackVisibleSources()
	{
		return this.visibleSourcesReadOnly;
	}

	public ObservableList< SourceAndConverter< ? > > trackVisibleSourcesAndConverters()
	{
		return this.visibleSourcesAndConverterReadOnly;
	}

	public ObjectProperty< Source< ? > > currentSourceProperty()
	{
		return this.currentSource;
	}

	public IntegerProperty currentSourceIndexProperty()
	{
		return this.currentSourceIndex;
	}

	public void moveSourceTo( final Source< ? > source, final int index )
	{
		if ( index >= 0 && index < sources.size() && sources.contains( source ) && sources.indexOf( source ) != index )
		{
			final ArrayList< Source< ? > > copy = new ArrayList<>( this.sources );
			copy.remove( source );
			copy.add( index, source );
			final Source< ? > currentSource = this.currentSource.get();
			final int currentSourceIndex = copy.indexOf( currentSource );
			this.sources.clear();
			this.sources.setAll( copy );
			this.currentSource.set( currentSource );
			this.currentSourceIndex.set( currentSourceIndex );
		}
	}

	public void moveSourceTo( final int from, final int to )
	{
		if ( from >= 0 && from < sources.size() )
			moveSourceTo( sources.get( from ), to );
	}

	private void modifyCurrentSourceIndex( final int amount )
	{
		if ( this.sources.size() == 0 )
			this.currentSourceIndex.set( -1 );
		else
		{
			final int newIndex = ( this.currentSourceIndex.get() + amount ) % this.sources.size();
			this.currentSourceIndex.set( newIndex < 0 ? this.sources.size() + newIndex : newIndex );
		}
	}

	public void incrementCurrentSourceIndex()
	{
		modifyCurrentSourceIndex( 1 );
	}

	public void decrementCurrentSourceIndex()
	{
		modifyCurrentSourceIndex( -1 );
	}

	public ObservableBooleanValue isCurrentSource( final Source< ? > source )
	{
		return Bindings.createBooleanBinding(
				() -> Optional.ofNullable( currentSource.get() ).map( source::equals ).orElse( false ),
				currentSource );
	}

	public ObservableIntegerValue currentSourceIndexInVisibleSources()
	{
		return this.currentSourceIndexInVisibleSources;
	}

	private void updateVisibleSources()
	{

		final List< Source< ? > > visibleSources = this.sources
				.stream()
				.filter( s -> states.get( s ).visibleProperty().get() )
				.collect( Collectors.toList() );
		this.visibleSources.setAll( visibleSources );
	}

	private void updateVisibleSourcesAndConverters()
	{
		this.visibleSourcesAndConverter.setAll( this.visibleSources
				.stream()
				.map( states::get )
				.map( AtlasSourceState::getSourceAndConverter )
				.collect( Collectors.toList() ) );
	}

	private void updateCurrentSourceIndexInVisibleSources()
	{
		this.currentSourceIndexInVisibleSources.set( this.visibleSources.indexOf( currentSource.get() ) );
	}

}