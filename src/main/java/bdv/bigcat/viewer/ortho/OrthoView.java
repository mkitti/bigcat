package bdv.bigcat.viewer.ortho;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import bdv.bigcat.viewer.ViewerActor;
import bdv.bigcat.viewer.panel.ViewerNode;
import bdv.bigcat.viewer.panel.ViewerNode.ViewerAxis;
import bdv.bigcat.viewer.panel.ViewerTransformManager;
import bdv.bigcat.viewer.viewer3d.Viewer3D;
import bdv.cache.CacheControl;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerOptions;
import bdv.viewer.ViewerPanel;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingNode;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import net.imglib2.realtransform.AffineTransform3D;

public class OrthoView extends BorderPane
{

	public static final Class< ? >[] FOCUS_KEEPERS = { TextField.class };

	private final HashSet< ViewerNode > viewerNodes = new HashSet<>();

	private final HashMap< ViewerNode, ViewerTransformManager > managers = new HashMap<>();

	private final StackPane root = new StackPane();

	private final GridPane grid;

	private final GridResizer resizer;

	private final OrthoViewState state;

//	private final ObservableList< SourceAndConverter< ? > > sourceLayers = FXCollections.observableArrayList();
//	{
//		sourceLayers.addListener( ( ListChangeListener< SourceAndConverter< ? > > ) c -> {
//			while ( c.next() );
//
//		} );
//	}

	private final ObservableList< ViewerActor > viewerActors = FXCollections.observableArrayList();
	{
		viewerActors.addListener( ( ListChangeListener< ViewerActor > ) c -> {
			while ( c.next() )
				if ( c.wasAdded() )
					for ( final ViewerActor actor : c.getAddedSubList() )
						viewerNodes.forEach( vn -> actor.onAdd().accept( ( ViewerPanel ) vn.getContent() ) );
				else if ( c.wasRemoved() )
					for ( final ViewerActor actor : c.getRemoved() )
						viewerNodes.forEach( vn -> actor.onRemove().accept( ( ViewerPanel ) vn.getContent() ) );
		} );
	}

	private final Consumer< ViewerPanel > onFocusEnter;

	private final Consumer< ViewerPanel > onFocusExit;

	public OrthoView()
	{
		this( new OrthoViewState() );
	}

	public OrthoView( final ViewerOptions viewerOptions )
	{
		this( new OrthoViewState( viewerOptions ) );
	}

	public OrthoView( final OrthoViewState state )
	{
		this( ( vp ) -> {}, ( vp ) -> {}, state );
	}

	public OrthoView( final Consumer< ViewerPanel > onFocusEnter, final Consumer< ViewerPanel > onFocusExit, final OrthoViewState state )
	{
		super();
		this.state = state;

		this.grid = this.state.constraintsManager.createGrid();
		this.centerProperty().set( this.root );
		this.onFocusEnter = onFocusEnter;
		this.onFocusExit = onFocusExit;
		this.grid.requestFocus();
		this.setInfoNode( new Label( "Place your node here!" ) );

//		final Pane dummyPane = new Pane();
		this.resizer = new GridResizer( this.state.constraintsManager, 10, grid );
		this.grid.setOnMouseMoved( resizer.onMouseMovedHandler() );
		this.grid.setOnMouseDragged( resizer.onMouseDraggedHandler() );
		this.grid.setOnMouseClicked( resizer.onMouseDoubleClickedHandler() );
		this.grid.setOnMousePressed( resizer.onMousePresedHandler() );
		this.grid.setOnMouseReleased( resizer.onMouseReleased() );
//		dummyPane.addEventHandler( EventType.ROOT, this.grid::fireEvent );
//		dummyPane.setPickOnBounds( false );
		this.root.getChildren().add( this.grid );
//		this.root.getChildren().add( dummyPane );
	}

	public void makeDefaultLayout() throws InterruptedException
	{
		addViewer( ViewerAxis.Z, 0, 0 );
		addViewer( ViewerAxis.X, 0, 1 );
		addViewer( ViewerAxis.Y, 1, 0 );
		this.grid.requestFocus();
		this.viewerNodes.stream().map( ViewerNode::getContent ).forEach( vp -> ( ( ViewerPanel ) vp ).setBackgroundCreator( new GradientBackgroundAlpha() ) );
//		this.viewerNodes.stream().map( ViewerNode::getContent ).forEach( vp -> ( ( ViewerPanel ) vp ).setBackgroundCreator( ( w, h ) -> Optional.empty() ) );
		new Thread( () -> {
			final AtomicInteger degrees = new AtomicInteger( 0 );
			final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
			scheduler.scheduleAtFixedRate( () -> {
				final int h = degrees.get();
				degrees.set( ( h + 3 ) % 360 );
				final Color c = Color.getHSBColor( h / 360.0f, 0.3f, 0.4f );
				viewerNodes.stream().map( ViewerNode::getContent ).forEach( v -> {
					final ViewerPanel vp = ( ViewerPanel ) v;
					vp.setBackground( c );
				} );
			}, 0, 200, TimeUnit.MILLISECONDS );
		} ).start();
	}

	public void setInfoNode( final Node node )
	{
		for ( final Node child : grid.getChildren() )
			if ( GridPane.getRowIndex( child ) == 1 && GridPane.getColumnIndex( child ) == 1 )
			{
				grid.getChildren().remove( child );
				break;
			}
		this.grid.add( node, 1, 1 );
	}

	public synchronized void addSourcesListener( final ListChangeListener< SourceAndConverter< ? > > listener )
	{
		this.state.sacs.addListener( listener );
	}

	public synchronized void addActor( final ViewerActor actor )
	{
		this.viewerActors.add( actor );
	}

	public Scene createScene( final int width, final int height )
	{
		final Scene scene = new Scene( this, width, height );
		scene.setOnKeyTyped( event -> {
			if ( event.getCharacter().equals( "f" ) )
				maximizeActiveOrthoView( scene, event );
		} );

		return scene;
	}

	private void addViewerNodesHandler( final ViewerNode viewerNode, final Class< ? >[] focusKeepers )
	{

		viewerNode.addEventHandler( MouseEvent.MOUSE_CLICKED, event -> viewerNode.requestFocus() );

		viewerNode.addEventHandler( MouseEvent.MOUSE_ENTERED, event -> {
			final Node focusOwner = viewerNode.sceneProperty().get().focusOwnerProperty().get();
			for ( final Class< ? > focusKeeper : focusKeepers )
				if ( focusKeeper.isInstance( focusOwner ) )
					return;
			viewerNode.requestFocus();
		} );

		handleFocusEvent( viewerNode );
	}

	private synchronized void handleFocusEvent( final ViewerNode viewerNode )
	{
		viewerNode.focusedProperty().addListener( ( ChangeListener< Boolean > ) ( observable, oldValue, newValue ) -> {
			final ViewerPanel viewer = ( ViewerPanel ) viewerNode.getContent();
			if ( viewer == null )
				return;
			else if ( newValue )
				this.onFocusEnter.accept( viewer );
			else
				this.onFocusExit.accept( viewer );
		} );
	}

	private synchronized void addViewer( final ViewerAxis axis, final int rowIndex, final int colIndex ) throws InterruptedException
	{
		final ViewerNode viewerNode = new ViewerNode( new CacheControl.Dummy(), axis, this.state.viewerOptions );
		this.viewerNodes.add( viewerNode );
		this.managers.put( viewerNode, viewerNode.manager() );
		viewerNode.getState().setSources( state.sacs, state.visibility, state.currentSource, state.interpolation );
		viewerNode.getState().setGlobalTransform( this.state.globalTransform );
		viewerActors.forEach( actor -> actor.onAdd().accept( ( ViewerPanel ) viewerNode.getContent() ) );
		addViewerNodesHandler( viewerNode, FOCUS_KEEPERS );

		this.grid.add( viewerNode, rowIndex, colIndex );
		viewerNode.setOnMouseClicked( resizer.onMouseDoubleClickedHandler() );
		viewerNode.setOnMousePressed( resizer.onMousePresedHandler() );
		viewerNode.setOnMouseDragged( resizer.onMouseDraggedHandler() );
		viewerNode.setOnMouseMoved( resizer.onMouseMovedHandler() );
	}

	private void maximizeActiveOrthoView( final Scene scene, final Event event )
	{
		final Node focusOwner = scene.focusOwnerProperty().get();
		if ( viewerNodes.contains( focusOwner ) )
			// event.consume();
			if ( !this.state.constraintsManager.isFullScreen() )
			{
				viewerNodes.forEach( node -> node.setVisible( node == focusOwner ) );
				this.state.constraintsManager.maximize(
						GridPane.getRowIndex( focusOwner ),
						GridPane.getColumnIndex( focusOwner ),
						0 );
				( ( ViewerPanel ) ( ( SwingNode ) focusOwner ).getContent() ).requestRepaint();
				grid.setHgap( 0 );
				grid.setVgap( 0 );
			}
			else
			{
				this.state.constraintsManager.resetToLast();
				viewerNodes.forEach( node -> node.setVisible( true ) );
				viewerNodes.forEach( node -> ( ( ViewerPanel ) node.getContent() ).requestRepaint() );
				grid.setHgap( 1 );
				grid.setVgap( 1 );
			}
	}

	public Node viewerNode()
	{
		final Viewer3D v3d = new Viewer3D( "test", 100, 100, false );
		Platform.runLater( () -> {
			v3d.init();
		} );
		return v3d.getPanel();
	}

//	public Node globalSourcesInfoNode()
//	{
//		final FlowPane p = new FlowPane( Orientation.VERTICAL );
//
//		final HashMap< Source< ? >, Node > sourceToEntry = new HashMap<>();
//
//		final Function< SourceAndConverter< ? >, Node > entryCreator = ( sac ) -> {
//			final FlowPane fp = new FlowPane();
//			fp.getChildren().add( new Label( sac.getSpimSource().getName() ) );
//
//			final Converter< ?, ARGBType > conv = sac.getConverter();
//
//			if ( conv instanceof RealARGBConverter )
//			{
//				final RealARGBConverter< ? > c = ( RealARGBConverter< ? > ) conv;
//				// alpha
//				{
//					final Spinner< Integer > sp = new Spinner<>( 0, 255, c.getAlpha() );
//					sp.valueProperty().addListener( ( ChangeListener< Integer > ) ( observable, oldValue, newValue ) -> {
//						c.setAlpha( newValue );
//						viewerNodes.forEach( vn -> ( ( ViewerPanel ) vn.getContent() ).requestRepaint() );
//					} );
//					sp.setEditable( true );
//					fp.getChildren().add( sp );
//				}
//
//				// min
//				{
//					final Spinner< Double > sp = new Spinner<>( Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, c.getMin() );
//					sp.valueProperty().addListener( ( ChangeListener< Double > ) ( observable, oldValue, newValue ) -> {
//						c.setMin( newValue );
//						viewerNodes.forEach( vn -> ( ( ViewerPanel ) vn.getContent() ).requestRepaint() );
//					} );
//					sp.setEditable( true );
//					fp.getChildren().add( sp );
//				}
//
//				// max
//				{
//					final Spinner< Double > sp = new Spinner<>( Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, c.getMax() );
//					sp.valueProperty().addListener( ( ChangeListener< Double > ) ( observable, oldValue, newValue ) -> {
//						c.setMax( newValue );
//						viewerNodes.forEach( vn -> ( ( ViewerPanel ) vn.getContent() ).requestRepaint() );
//					} );
//					sp.setEditable( true );
//					fp.getChildren().add( sp );
//				}
//			}
//
//			else if ( conv instanceof HighlightingStreamConverter )
//			{
//				final HighlightingStreamConverter c = ( HighlightingStreamConverter ) conv;
//
//				// alpha
//				{
//					final Spinner< Integer > sp = new Spinner<>( 0, 255, c.getAlpha() );
//					sp.valueProperty().addListener( ( ChangeListener< Integer > ) ( observable, oldValue, newValue ) -> {
//						c.setAlpha( newValue );
//						viewerNodes.forEach( vn -> ( ( ViewerPanel ) vn.getContent() ).requestRepaint() );
//					} );
//					sp.setEditable( true );
//					fp.getChildren().add( sp );
//				}
//
//				// highlighting alpha
//				{
//					final Spinner< Integer > sp = new Spinner<>( 0, 255, c.getHighlightAlpha() );
//					sp.valueProperty().addListener( ( ChangeListener< Integer > ) ( observable, oldValue, newValue ) -> {
//						c.setHighlightAlpha( newValue );
//						viewerNodes.forEach( vn -> ( ( ViewerPanel ) vn.getContent() ).requestRepaint() );
//					} );
//					sp.setEditable( true );
//					fp.getChildren().add( sp );
//				}
//
//				// invalid alpha
//				{
//					final Spinner< Integer > sp = new Spinner<>( 0, 255, c.getInvalidSegmentAlpha() );
//					sp.valueProperty().addListener( ( ChangeListener< Integer > ) ( observable, oldValue, newValue ) -> {
//						c.setInvalidSegmentAlpha( newValue );
//						viewerNodes.forEach( vn -> ( ( ViewerPanel ) vn.getContent() ).requestRepaint() );
//					} );
//					sp.setEditable( true );
//					fp.getChildren().add( sp );
//				}
//			}
//
//			sourceToEntry.put( sac.getSpimSource(), fp );
//			p.getChildren().add( fp );
//
//			return fp;
//		};
//		for ( final SourceAndConverter< ? > source : this.state.viewerPanelState.getSourcesCopy() )
//			entryCreator.apply( source );
//
//		this.state.viewerPanelState.addSourcesListener( c -> {
//			while ( c.next() )
//				if ( c.wasRemoved() )
//					c.getRemoved().forEach( rm -> p.getChildren().remove( sourceToEntry.remove( rm.getSpimSource() ) ) );
//				else if ( c.wasAdded() )
//					c.getAddedSubList().forEach( entryCreator::apply );
//
//		} );
//
//		return p;
//	}

	public void setTransform( final AffineTransform3D transform )
	{
		this.state.globalTransform.setTransform( transform );
	}

	public OrthoViewState getState()
	{
		return this.state;
	}

	public void addAction( final Runnable r, final String name, final String... keyStrokes )
	{
		viewerNodes.forEach( viewerNode -> viewerNode.addAction( r, name, keyStrokes ) );
	}
}