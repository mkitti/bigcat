package bdv.bigcat.viewer.atlas.control;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.label.Label;
import bdv.bigcat.viewer.atlas.control.paint.FloodFill;
import bdv.bigcat.viewer.atlas.control.paint.Paint2D;
import bdv.bigcat.viewer.atlas.control.paint.RestrictPainting;
import bdv.bigcat.viewer.atlas.control.paint.SelectNextId;
import bdv.bigcat.viewer.atlas.data.mask.CannotPersist;
import bdv.bigcat.viewer.atlas.data.mask.MaskedSource;
import bdv.bigcat.viewer.atlas.source.SourceInfo;
import bdv.bigcat.viewer.bdvfx.EventFX;
import bdv.bigcat.viewer.bdvfx.InstallAndRemove;
import bdv.bigcat.viewer.bdvfx.KeyTracker;
import bdv.bigcat.viewer.bdvfx.ViewerPanelFX;
import bdv.bigcat.viewer.state.GlobalTransformManager;
import bdv.bigcat.viewer.state.SelectedIds;
import bdv.viewer.Source;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;

public class Paint implements ToOnEnterOnExit
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final HashMap< ViewerPanelFX, Collection< InstallAndRemove< Node > > > mouseAndKeyHandlers = new HashMap<>();

	private final HashMap< ViewerPanelFX, Paint2D > painters = new HashMap<>();

	private final SourceInfo sourceInfo;

	private final KeyTracker keyTracker;

	private final GlobalTransformManager manager;

	private final SimpleDoubleProperty brushRadius = new SimpleDoubleProperty( 5.0 );

	private final SimpleDoubleProperty brushRadiusIncrement = new SimpleDoubleProperty( 1.0 );

	private final Runnable requestRepaint;

	private final BooleanProperty paint3D = new SimpleBooleanProperty( false );

	private final BooleanBinding paint2D = paint3D.not();

	public Paint(
			final SourceInfo sourceInfo,
			final KeyTracker keyTracker,
			final GlobalTransformManager manager,
			final Runnable requestRepaint )
	{
		super();
		this.sourceInfo = sourceInfo;
		this.keyTracker = keyTracker;
		this.manager = manager;
		this.requestRepaint = requestRepaint;
	}

	@Override
	public Consumer< ViewerPanelFX > getOnEnter()
	{
		return t -> {
//			if ( this.paintableViews.contains( this.viewerAxes.get( t ) ) )
			{
				if ( !this.mouseAndKeyHandlers.containsKey( t ) )
				{
					final Paint2D paint2D = new Paint2D( t, sourceInfo, manager, requestRepaint );
					paint2D.brushRadiusProperty().set( this.brushRadius.get() );
					paint2D.brushRadiusProperty().bindBidirectional( this.brushRadius );
					paint2D.brushRadiusIncrementProperty().set( this.brushRadiusIncrement.get() );
					paint2D.brushRadiusIncrementProperty().bindBidirectional( this.brushRadiusIncrement );
					final ObjectProperty< Source< ? > > currentSource = sourceInfo.currentSourceProperty();
					final ObjectBinding< SelectedIds > currentSelectedIds = Bindings.createObjectBinding(
							() -> sourceInfo.getState( currentSource.get() ).selectedIdsProperty().get(),
							currentSource );

					final Supplier< Long > paintSelection = () -> {
						final SelectedIds csi = currentSelectedIds.get();

						if ( csi == null )
						{
							LOG.debug( "Source {} does not provide selected ids.", currentSource.get() );
							return null;
						}

						final long lastSelection = csi.getLastSelection();
						return lastSelection == Label.INVALID ? null : lastSelection;
					};

					painters.put( t, paint2D );

					final FloodFill fill = new FloodFill( t, sourceInfo, requestRepaint );

					final RestrictPainting restrictor = new RestrictPainting( t, sourceInfo, requestRepaint );

					final List< InstallAndRemove< Node > > iars = new ArrayList<>();

					iars.add( EventFX.KEY_PRESSED( "show brush overlay", event -> paint2D.showBrushOverlay(), event -> keyTracker.areKeysDown( KeyCode.SPACE ) ) );
					iars.add( EventFX.KEY_RELEASED( "show brush overlay", event -> paint2D.hideBrushOverlay(), event -> event.getCode().equals( KeyCode.SPACE ) && !keyTracker.areKeysDown( KeyCode.SPACE ) ) );
					iars.add( EventFX.SCROLL( "change brush size", event -> paint2D.changeBrushRadius( event.getDeltaY() ), event -> keyTracker.areOnlyTheseKeysDown( KeyCode.SPACE ) ) );

					iars.add( EventFX.MOUSE_PRESSED( "paint click 2D", e -> paint2D.prepareAndPaintUnchecked( e, paintSelection.get() ), event -> keyTracker.areOnlyTheseKeysDown( KeyCode.SPACE ) && this.paint2D.get() ) );

					iars.add( paint2D.dragPaintLabel( "paint 2D", paintSelection::get, event -> event.isPrimaryButtonDown() && keyTracker.areOnlyTheseKeysDown( KeyCode.SPACE ) && this.paint2D.get() ) );

					iars.add( paint2D.dragPaintLabel( "erase canvas 2D", () -> Label.TRANSPARENT, event -> event.isSecondaryButtonDown() && keyTracker.areOnlyTheseKeysDown( KeyCode.SPACE ) && this.paint2D.get() ) );

					iars.add( paint2D.dragPaintLabel( "to background 2D", () -> Label.BACKGROUND, event -> event.isSecondaryButtonDown() && keyTracker.areOnlyTheseKeysDown( KeyCode.SPACE, KeyCode.SHIFT ) && this.paint2D.get() ) );

					iars.add( EventFX.MOUSE_PRESSED( "fill", event -> fill.fillAt( event.getX(), event.getY(), paintSelection::get ), event -> event.isPrimaryButtonDown() && keyTracker.areOnlyTheseKeysDown( KeyCode.SHIFT, KeyCode.F ) ) );
					iars.add( EventFX.MOUSE_PRESSED( "restrict", event -> restrictor.restrictTo( event.getX(), event.getY() ), event -> event.isPrimaryButtonDown() && keyTracker.areOnlyTheseKeysDown( KeyCode.SHIFT, KeyCode.R ) ) );

					final SelectNextId nextId = new SelectNextId( sourceInfo );
					iars.add( EventFX.KEY_PRESSED( "next id", event -> nextId.getNextId(), event -> keyTracker.areOnlyTheseKeysDown( KeyCode.N ) ) );

					iars.add( EventFX.KEY_PRESSED( "merge canvas", event -> {
						final Source< ? > cs = currentSource.get();
						if ( cs instanceof MaskedSource< ?, ? > )
						{
							LOG.debug( "Merging canvas for source {}", cs );
							final MaskedSource< ?, ? > mcs = ( MaskedSource< ?, ? > ) cs;
							try
							{
								mcs.persistCanvas();
							}
							catch ( final CannotPersist e )
							{
								LOG.warn( "Could not persist canvas. Try again later." );
							}
						}
						event.consume();
					}, event -> keyTracker.areOnlyTheseKeysDown( KeyCode.SHIFT, KeyCode.M ) ) );

					this.mouseAndKeyHandlers.put( t, iars );
				}
				this.mouseAndKeyHandlers.get( t ).forEach( handler -> {
					handler.installInto( t );
				} );
			}

		};
	}

	public BooleanProperty paint3DProperty()
	{
		return this.paint3D;
	}

	@Override
	public Consumer< ViewerPanelFX > getOnExit()
	{
		return t -> {
			if ( this.mouseAndKeyHandlers.containsKey( t ) )
			{
				if ( painters.containsKey( t ) )
					painters.get( t ).setBrushOverlayVisible( false );
				this.mouseAndKeyHandlers.get( t ).forEach( handler -> {
					handler.removeFrom( t );
				} );
			}
		};
	}

}