/* DesktopImpl.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Wed Jun 22 09:50:57     2005, Created by tomyeh
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 3.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package org.zkoss.zk.ui.impl;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;

import org.zkoss.lang.D;
import org.zkoss.lang.Strings;
import org.zkoss.lang.Objects;
import org.zkoss.util.CacheMap;
import org.zkoss.util.Cache;
import org.zkoss.util.logging.Log;
import org.zkoss.util.media.Media;
import org.zkoss.io.Serializables;

import org.zkoss.zk.mesg.MZk;
import org.zkoss.zk.ui.WebApp;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Sessions;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.ComponentNotFoundException;
import org.zkoss.zk.ui.DesktopUnavailableException;
import org.zkoss.zk.ui.metainfo.LanguageDefinition;
import org.zkoss.zk.ui.util.Configuration;
import org.zkoss.zk.ui.util.DesktopCleanup;
import org.zkoss.zk.ui.util.ExecutionCleanup;
import org.zkoss.zk.ui.util.ExecutionInit;
import org.zkoss.zk.ui.util.UiLifeCycle;
import org.zkoss.zk.ui.util.Monitor;
import org.zkoss.zk.ui.util.DesktopSerializationListener;
import org.zkoss.zk.ui.util.EventInterceptor;
import org.zkoss.zk.ui.util.AuRequestProcessor;
import org.zkoss.zk.ui.event.*;
import org.zkoss.zk.ui.ext.render.DynamicMedia;
import org.zkoss.zk.ui.sys.PageCtrl;
import org.zkoss.zk.ui.sys.SessionCtrl;
import org.zkoss.zk.ui.sys.ExecutionCtrl;
import org.zkoss.zk.ui.sys.ComponentCtrl;
import org.zkoss.zk.ui.sys.ComponentsCtrl;
import org.zkoss.zk.ui.sys.RequestQueue;
import org.zkoss.zk.ui.sys.DesktopCache;
import org.zkoss.zk.ui.sys.WebAppCtrl;
import org.zkoss.zk.ui.sys.DesktopCtrl;
import org.zkoss.zk.ui.sys.EventProcessingThread;
import org.zkoss.zk.ui.sys.IdGenerator;
import org.zkoss.zk.ui.sys.ServerPush;
import org.zkoss.zk.ui.impl.EventInterceptors;
import org.zkoss.zk.au.AuRequest;
import org.zkoss.zk.au.out.AuBookmark;
import org.zkoss.zk.device.Device;
import org.zkoss.zk.device.Devices;
import org.zkoss.zk.device.DeviceNotFoundException;

/**
 * The implementation of {@link Desktop}.
 *
 * <p>Note: though {@link DesktopImpl} is serializable, it is designed
 * to work with Web container to enable the serialization of sessions.
 * It is not suggested to serialize and desrialize it directly since
 * many fields might be lost.
 *
 * <p>On the other hand, it is OK to serialize and deserialize
 * {@link Component}.
 *
 * @author tomyeh
 */
public class DesktopImpl implements Desktop, DesktopCtrl, java.io.Serializable {
	private static final Log log = Log.lookup(DesktopImpl.class);
    private static final long serialVersionUID = 20081209L;

	/** Represents media stored with {@link #getDownloadMediaURI}.
	 * It must be distinguishable from component's ID.
	 */
	private static final String DOWNLOAD_PREFIX = "dwnmed-";

	private transient WebApp _wapp;
	private transient Session _sess;
	private String _id;
	/** The current directory of this desktop. */
	private String _dir = "";
	/** The path of the request that causes this desktop to be created. */
	private final String _path;
	/** The URI to access the update engine. */
	private final String _updateURI;
	/** Map(String id, Page page). */
	private final Map _pages = new LinkedHashMap(3);
	/** Map (String uuid, Component comp). */
	private transient Map _comps;
	/** A map of attributes. */
	private transient Map _attrs;
		//don't create it dynamically because PageImp._ip bind it at constructor
	private transient Execution _exec;
	/** Next available key. */
	private int _nextKey;
	/** Next available UUID. */
	private int _nextUuid;
	/** A special prefix to UUID generated by this desktop.
	 * It is used to avoid ID conflicts with other desktops in the same
	 * session.
	 * Since UUID is long enough plus this prefix, the chance to conlict
	 * is almost impossible.
	 */
	private String _uuidPrefix;
	/** The request queue. */
	private transient RequestQueue _rque;
	private String _bookmark = "";
	/** The device type. */
	private String _devType = "ajax";
	/** The device. */
	private transient Device _dev; //it will re-init each time getDevice called
	/** A map of media (String key, Media content). */
	private CacheMap _meds;
	/** ID used to identify what is stored in _meds. */
	private int _medId;
	/** The server push controller, or null if not enabled. */
	private transient ServerPush _spush;
	/** The event interceptors. */
	private final EventInterceptors _eis = new EventInterceptors();
	private transient List _dtCleans, _execInits, _execCleans,
		_uiCycles, _auProcs;
	private transient Map _lastRes;
	private static final int MAX_RESPONSE_ID = 999;
	/** The response sequence ID. */
	private int _resId; //so next will be 1
	/** Whether any onPiggyback listener is registered. */
	private boolean _piggybackListened;
	/** Whether the server push shall stop after deactivate. */
	private boolean _spushShallStop;

	/**
	 * @param updateURI the URI to access the update engine (no expression allowed).
	 * Note: it is NOT encoded yet.
	 * @param path the path that causes this desktop to create.
	 * If null or empty is specified, it means not available.
	 * @param deviceType the device type.
	 * If null or empty is specified, "ajax" is assumed.
	 * @since 3.0.1
	 */
	public DesktopImpl(WebApp wapp, String updateURI, String path,
	String deviceType, Object request) {
		if (updateURI == null || wapp == null)
			throw new IllegalArgumentException("null");

		//Feature 1811241: we create a temporary exec (in WebManager.newDesktop),
		//so DesktopInit can access Executions.getCurrent
		final Execution exec = Executions.getCurrent();
		if (exec != null)
			((ExecutionCtrl)exec).setDesktop(this);

		_wapp = wapp;
		_updateURI = updateURI;
		init();
		_sess = Sessions.getCurrent(); //must be the current session

		String dir = null;
		if (path != null) {
			_path = path;
			final int j = path.lastIndexOf('/');
			if (j >= 0) dir = path.substring(0, j + 1);
		} else {
			_path = "";
		}
		setCurrentDirectory(dir);
		if (deviceType != null && deviceType.length() != 0)
			setDeviceType(deviceType);

		final Configuration config = _wapp.getConfiguration();
		_exec = exec; //fake
		try {
			final WebAppCtrl wappc = (WebAppCtrl)_wapp;
			final DesktopCache dc = wappc.getDesktopCache(_sess);
			final IdGenerator idgen = wappc.getIdGenerator();
			if (idgen != null)
				_id = idgen.nextDesktopId(this);
			if (_id == null)
				_id = Strings.encode(
					new StringBuffer(12).append("g"), dc.getNextKey()).toString();
			updateUuidPrefix();

			config.invokeDesktopInits(this, request); //it might throw exception
			if (exec.isVoided()) return; //sendredirect or forward

			dc.addDesktop(this); //add to cache after invokeDesktopInits

			final Monitor monitor = config.getMonitor();
			if (monitor != null) {
				try {
					monitor.desktopCreated(this);
				} catch (Throwable ex) {
					log.error(ex);
				}
			}
		} finally {
			_exec = null;
		}
	}
	/** Initialization for contructor and de-serialized. */
	private void init() {
		_rque = newRequestQueue();
		_comps = new HashMap(64);
		_attrs = new HashMap();
		_lastRes = new HashMap(4);
	}
	/** Updates _uuidPrefix based on _id. */
	private void updateUuidPrefix() {
		_uuidPrefix = _id.substring(1, _id.length() <= 2 ? 2: 3);
			//the first few chars because of the encode's algorithm
	}

	public String getId() {
		return _id;
	}

	/** Creates the request queue.
	 * It is called when the desktop is initialized.
	 *
	 * <p>You may override it to provide your implementation of
	 * {@link RequestQueue} to control how to optimize the AU requests.
	 *
	 * <p>Default: creates an instance from {@link RequestQueueImpl};
	 *
	 * @since 2.4.0
	 */
	protected RequestQueue newRequestQueue() {
		return new RequestQueueImpl();
	}

	//-- Desktop --//
	public String getDeviceType() {
		return _devType;
	}
	public Device getDevice() {
		if (_dev == null)
			_dev = Devices.getDevice(_devType);
		return _dev;
	}
	public void setDeviceType(String deviceType) {
		//Note: we check _comps.isEmpty() only if device type diffs, because
		//a desktop might have several richlet and each of them will call
		//this method once
		if (!_devType.equals(deviceType)) {
			if (deviceType == null || deviceType.length() == 0)
				throw new IllegalArgumentException("empty");
			if (!Devices.exists(deviceType))
				throw new DeviceNotFoundException(deviceType, MZk.NOT_FOUND, deviceType);

			if (!_comps.isEmpty())
				throw new UiException("Unable to change the device type since some components are attached.");
			_devType = deviceType;
			_dev = null;

			((SessionCtrl)_sess).setDeviceType(_devType);
		}
	}
	public Execution getExecution() {
		return _exec;
	}
	public final Session getSession() {
		return _sess;
	}
	public String getUpdateURI(String pathInfo) {
		final String uri;
		if (pathInfo == null || pathInfo.length() == 0) {
			uri = _updateURI;
		} else {
			if (pathInfo.charAt(0) != '/')
				pathInfo = '/' + pathInfo;
			uri = _updateURI + pathInfo;
		}
		return _exec.encodeURL(uri);
	}
	public String getDynamicMediaURI(Component comp, String pathInfo) {
		if (!(((ComponentCtrl)comp).getExtraCtrl() instanceof DynamicMedia))
			throw new UiException(DynamicMedia.class+" not implemented by getExtraCtrl() of "+comp);

		final StringBuffer sb = new StringBuffer(64)
			.append("/view/").append(getId())
			.append('/').append(comp.getUuid());

		if (pathInfo != null && pathInfo.length() > 0) {
			if (!pathInfo.startsWith("/")) sb.append('/');
			sb.append(pathInfo);
		}
		return getUpdateURI(sb.toString());
	}
	public String getDownloadMediaURI(Media media, String pathInfo) {
		if (media == null)
			throw new IllegalArgumentException("null media");

		if (_meds == null) {
			_meds = new CacheMap();
			_meds.setMaxSize(1024);
			_meds.setLifetime(15 * 60 * 1000);
				//15 minutes (CONSIDER: configurable)
		} else {
			housekeep();
		}

		String medId = Strings.encode(
			new StringBuffer(12).append(DOWNLOAD_PREFIX), _medId++).toString();
		_meds.put(medId, media);

		final StringBuffer sb = new StringBuffer(64)
			.append("/view/").append(getId())
			.append('/').append(medId);

		if (pathInfo != null && pathInfo.length() > 0) {
			if (!pathInfo.startsWith("/")) sb.append('/');
			sb.append(pathInfo);
		}
		return getUpdateURI(sb.toString());
	}
	public Media getDownloadMedia(String medId, boolean reserved) {
		return _meds != null ? (Media)_meds.get(medId): null;
	}
	/** Cleans up redudant data. */
	private void housekeep() {
		if (_meds != null) _meds.expunge();
	}

	public Page getPage(String pageId) {
		//We allow user to access this method concurrently, so synchronized
		//is required
		final Page page = getPageIfAny(pageId);
		if (page == null)
			throw new ComponentNotFoundException("Page not found: "+pageId);
		return page;
	}
	public Page getPageIfAny(String pageId) {
		synchronized (_pages) {
			return (Page)_pages.get(pageId);
		}
	}
	public boolean hasPage(String pageId) {
		return _pages.containsKey(pageId);
	}
	public Collection getPages() {
		//No synchronized is required because it cannot be access concurrently
		return _pages.values();
	}

	public String getBookmark() {
		return _bookmark;
	}
	public void setBookmark(String name) {
		if (_exec == null)
			throw new IllegalStateException("Not the current desktop: "+this);
		if (name.indexOf('#') >= 0 || name.indexOf('?') >= 0)
			throw new IllegalArgumentException("Illegal character: # ?");
		_bookmark = name;
		((WebAppCtrl)_wapp).getUiEngine()
			.addResponse("bookmark", new AuBookmark(name));
	}

	public Collection getComponents() {
		return _comps.values();
	}
	public Component getComponentByUuid(String uuid) {
		final Component comp = (Component)_comps.get(uuid);
		if (comp == null)
			throw new ComponentNotFoundException("Component not found: "+uuid);
		return comp;
	}
	public Component getComponentByUuidIfAny(String uuid) {
		return (Component)_comps.get(uuid);
	}
	public void addComponent(Component comp) {
		//to avoid misuse, check whether new comp belongs to the same device type
		final LanguageDefinition langdef =
			comp.getDefinition().getLanguageDefinition();
		if (langdef != null && !_devType.equals(langdef.getDeviceType()))
			throw new UiException("Component, "+comp+", does not belong to the same device type of the desktop, "+_devType);

		final Object old = _comps.put(comp.getUuid(), comp);
		if (old != comp && old != null) {
			_comps.put(((Component)old).getUuid(), old); //recover
			throw new InternalError("Caller shall prevent it: Register a component twice: "+comp);
		}
	}
	public void removeComponent(Component comp) {
		_comps.remove(comp.getUuid());
	}

	public Map getAttributes() {
		return _attrs;
	}
	public Object getAttribute(String name) {
		return _attrs.get(name);
	}
	public Object setAttribute(String name, Object value) {
		return value != null ? _attrs.put(name, value): removeAttribute(name);
	}
	public Object removeAttribute(String name) {
		return _attrs.remove(name);
	}

	public WebApp getWebApp() {
		return _wapp;
	}

	public String getRequestPath() {
		return _path;
	}
	public String getCurrentDirectory() {
		return _dir;
	}
	public void setCurrentDirectory(String dir) {
		if (dir == null) {
			dir = "";
		} else {
			final int len = dir.length() - 1;
			if (len >= 0 && dir.charAt(len) != '/')
				dir += '/';
		}
		_dir = dir;
	}

	//-- DesktopCtrl --//
	public RequestQueue getRequestQueue() {
		housekeep();
		return _rque;
	}
	public void setExecution(Execution exec) {
		_exec = exec;
	}
	public void process(AuRequest request, boolean everError) {
		if (_auProcs != null)
			for (Iterator it = _auProcs.iterator(); it.hasNext();)
				if (((AuRequestProcessor)it.next()).process(request, everError))
					return; //done

		final String name = request.getName();
		if (Events.ON_BOOKMARK_CHANGE.equals(name)) {
			BookmarkEvent evt = BookmarkEvent.getBookmarkEvent(request);
			Events.postEvent(evt);
			Events.postEvent(new BookmarkEvent("onBookmarkChanged", evt.getBookmark()));
				//backward compatible
		} else if (Events.ON_CLIENT_INFO.equals(name)) {
			Events.postEvent(ClientInfoEvent.getClientInfoEvent(request));
		} else if ("rmDesktop".equals(name)) {
			((WebAppCtrl)request.getDesktop().getWebApp())
				.getUiEngine().setAbortingReason(
					new org.zkoss.zk.ui.impl.AbortByRemoveDesktop());
				//to avoid surprise, we don't remove it now
				//rather, it is done by AbortByRemoveDesktop.getResponse
		} else if ("redraw".equals(name)) {
			invalidate();
		} else
			Events.postEvent(Event.getEvent(request));
	}

	public int getNextKey() {
		return _nextKey++;
	}
	public String getNextUuid() {
		return ComponentsCtrl.toAutoId(_uuidPrefix, _nextUuid++);
	}

	public void addPage(Page page) {
		//We have to synchronize it due to getPage allows concurrent access
		synchronized (_pages) {
			final Object old = _pages.put(page.getId(), page);
			if (old != null) {
				_pages.put(((Page)old).getId(), old); //recover
				log.warning(
					page == old ? "Register a page twice: "+page:
						"Replicated ID: "+page+"; already used by "+old);
				return;
			}
//			if (D.ON && log.debugable()) log.debug("After added, pages: "+_pages);
		}
		afterPageAttached(page, this);
		_wapp.getConfiguration().afterPageAttached(page, this);
	}
	public void removePage(Page page) {
		synchronized (_pages) {
			if (_pages.remove(page.getId()) == null) {
				log.warning("Removing non-exist page: "+page+"\nCurrent pages: "+_pages.values());
				return;
			}
//			if (D.ON && log.debugable()) log.debug("After removed, pages: "+_pages.values());
		}
		removeComponents(page.getRoots());

		afterPageDetached(page, this);
		_wapp.getConfiguration().afterPageDetached(page, this);

		((PageCtrl)page).destroy();
	}
	private void removeComponents(Collection comps) {
		for (Iterator it = comps.iterator(); it.hasNext();) {
			final Component comp = (Component)it.next();
			removeComponents(comp.getChildren()); //recursive
			removeComponent(comp);
		}
	}

	public void setBookmarkByClient(String name) {
		_bookmark = name != null ? name: "";
	}

	public void setId(String id) {
		if (!((ExecutionCtrl)_exec).isRecovering())
			throw new IllegalStateException("Callable only in recovring");
		if (id == null || id.length() <= 1 || id.charAt(0) != 'g')
			throw new IllegalArgumentException("Invalid desktop ID. You have to recover to the original value, not creating a new value: "+id);

		final DesktopCache dc = ((WebAppCtrl)_wapp).getDesktopCache(_sess);
		dc.removeDesktop(this);

		_id = id;
		updateUuidPrefix();

		dc.addDesktop(this);
	}
	public void recoverDidFail(Throwable ex) {
		((WebAppCtrl)_wapp).getDesktopCache(_sess).removeDesktop(this);
	}

	public void destroy() {
		for (Iterator it = _pages.values().iterator(); it.hasNext();) {
			final PageCtrl pgc = (PageCtrl)it.next();
			try {
				pgc.destroy();
			} catch (Throwable ex) {
				log.error("Failed to destroy "+pgc, ex);
			}
		}

		if (_spush != null) {
			_spush.stop();
			_spush = null;
		}

		//theorectically, the following is not necessary, but, to be safe...
		_pages.clear();
		_attrs.clear();
		_comps = new HashMap(2); //not clear() since # of comps might huge
		_meds = null;
		_rque = null;
		//_sess = null; => not sure whether it can be nullify
		//_wapp = null; => SimpleDesktopCache.desktopDestroyed depends on it
	}
	public boolean isAlive() {
		return _rque != null;
	}

	public Collection getSuspendedThreads() {
		return ((WebAppCtrl)_wapp).getUiEngine().getSuspendedThreads(this);
	}
	public boolean ceaseSuspendedThread(EventProcessingThread evtthd, String cause) {
		return ((WebAppCtrl)_wapp).getUiEngine()
			.ceaseSuspendedThread(this, evtthd, cause);
	}

	//-- Object --//
	public String toString() {
		return "[Desktop "+_id+']';
	}

	public void sessionWillPassivate(Session sess) {
		for (Iterator it = _pages.values().iterator(); it.hasNext();)
			((PageCtrl)it.next()).sessionWillPassivate(this);

		if (_dev != null) _dev.sessionWillPassivate(this);
	}
	public void sessionDidActivate(Session sess) {
		_sess = sess;
		_wapp = sess.getWebApp();

		if (_dev != null) _dev.sessionDidActivate(this);

		for (Iterator it = _pages.values().iterator(); it.hasNext();)
			((PageCtrl)it.next()).sessionDidActivate(this);
	}

	//-- Serializable --//
	//NOTE: they must be declared as private
	private synchronized void writeObject(java.io.ObjectOutputStream s)
	throws java.io.IOException {
		s.defaultWriteObject();

		willSerialize(_attrs.values());
		Serializables.smartWrite(s, _attrs);
		willSerialize(_dtCleans);
		Serializables.smartWrite(s, _dtCleans);
		willSerialize(_execInits);
		Serializables.smartWrite(s, _execInits);
		willSerialize(_execCleans);
		Serializables.smartWrite(s, _execCleans);
		willSerialize(_uiCycles);
		Serializables.smartWrite(s, _uiCycles);
		willSerialize(_auProcs);
		Serializables.smartWrite(s, _auProcs);

		s.writeBoolean(_spush != null);
	}
	private void willSerialize(Collection c) {
		if (c != null)
			for (Iterator it = c.iterator(); it.hasNext();)
				willSerialize(it.next());
	}
	private void willSerialize(Object o) {
		if (o instanceof DesktopSerializationListener)
			((DesktopSerializationListener)o).willSerialize(this);
	}
	private synchronized void readObject(java.io.ObjectInputStream s)
	throws java.io.IOException, ClassNotFoundException {
		s.defaultReadObject();

		init();

		//get back _comps from _pages
		for (Iterator it = _pages.values().iterator(); it.hasNext();)
			for (Iterator e = ((Page)it.next()).getRoots().iterator();
			e.hasNext();)
				addAllComponents((Component)e.next());

		Serializables.smartRead(s, _attrs);
		didDeserialize(_attrs.values());
		_dtCleans = (List)Serializables.smartRead(s, _dtCleans);
		didDeserialize(_dtCleans);
		_execInits = (List)Serializables.smartRead(s, _execInits);
		didDeserialize(_execInits);
		_execCleans = (List)Serializables.smartRead(s, _execCleans);
		didDeserialize(_execCleans);
		_uiCycles = (List)Serializables.smartRead(s, _uiCycles);
		didDeserialize(_uiCycles);
		_auProcs = (List)Serializables.smartRead(s, _auProcs);
		didDeserialize(_auProcs);

		if (s.readBoolean())
			enableServerPush(true);
	}
	private void didDeserialize(Collection c) {
		if (c != null)
			for (Iterator it = c.iterator(); it.hasNext();)
				didDeserialize(it.next());
	}
	private void didDeserialize(Object o) {
		if (o instanceof DesktopSerializationListener)
			((DesktopSerializationListener)o).didDeserialize(this);
	}
	private void addAllComponents(Component comp) {
		addComponent(comp);
		for (Iterator it = comp.getChildren().iterator(); it.hasNext();)
			addAllComponents((Component)it.next());
	}

	public void addListener(Object listener) {
		boolean added = false;
		if (listener instanceof EventInterceptor) {
			_eis.addEventInterceptor((EventInterceptor)listener);
			added = true;
		}

		if (listener instanceof DesktopCleanup) {
			_dtCleans = addListener0(_dtCleans, listener);
			added = true;
		}

		if (listener instanceof ExecutionInit) {
			_execInits = addListener0(_execInits, listener);
			added = true;
		}
		if (listener instanceof ExecutionCleanup) {
			_execCleans = addListener0(_execCleans, listener);
			added = true;
		}

		if (listener instanceof UiLifeCycle) {
			_uiCycles = addListener0(_uiCycles, listener);
			added = true;
		}

		if (listener instanceof AuRequestProcessor) {
			_auProcs = addListener0(_auProcs, listener);
			added = true;
		}

		if (!added)
			throw new IllegalArgumentException("Unknown listener: "+listener);
	}
	private List addListener0(List list, Object listener) {
		if (list == null)
			list = new LinkedList();
		list.add(listener);
		return list;
	}
	public boolean removeListener(Object listener) {
		boolean found = false;
		if (listener instanceof EventInterceptor
		&& _eis.removeEventInterceptor((EventInterceptor)listener))
			found = true;

		if (listener instanceof DesktopCleanup
		&& removeListener0(_dtCleans, listener))
			found = true;

		if (listener instanceof ExecutionInit
		&& removeListener0(_execInits, listener))
			found = true;

		if (listener instanceof ExecutionCleanup
		&& removeListener0(_execCleans, listener))
			found = true;

		if (listener instanceof UiLifeCycle
		&& removeListener0(_uiCycles, listener))
			found = true;

		if (listener instanceof AuRequestProcessor
		&& removeListener0(_auProcs, listener))
			found = true;
		return found;
	}
	private boolean removeListener0(List list, Object listener) {
		//Since 3.0.6: To be consistent with Configuration,
		//use equals instead of ==
		if (list != null && listener != null)
			for (Iterator it = list.iterator(); it.hasNext();) {
				if (it.next().equals(listener)) {
					it.remove();
					return true;
				}
			}
		return false;
	}
	/** @deprecated As of release 3.0.6, replaced by {@link #addListener}.
	 */
	public void addEventInterceptor(EventInterceptor ei) {
		addListener(ei);
	}
	/** @deprecated As of release 3.0.6, replaced by {@link #removeListener}.
	 */
	public boolean removeEventInterceptor(EventInterceptor ei) {
		return removeListener(ei);
	}
	public Event beforeSendEvent(Event event) {
		event = _eis.beforeSendEvent(event);
		if (event != null)
			event = _wapp.getConfiguration().beforeSendEvent(event);
		return event;
	}
	public Event beforePostEvent(Event event) {
		event = _eis.beforePostEvent(event);
		if (event != null)
			event = _wapp.getConfiguration().beforePostEvent(event);
		return event;
	}
	public Event beforeProcessEvent(Event event) {
		event = _eis.beforeProcessEvent(event);
		if (event != null)
			event = _wapp.getConfiguration().beforeProcessEvent(event);
		return event;
	}
	public void afterProcessEvent(Event event) {
		_eis.afterProcessEvent(event);
		_wapp.getConfiguration().afterProcessEvent(event);
	}

	public void invokeDesktopCleanups() {
		if (_dtCleans != null) {
			for (Iterator it = _dtCleans.iterator(); it.hasNext();) {
				final DesktopCleanup listener = (DesktopCleanup)it.next();
				try {
					listener.cleanup(this);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
				}
			}
		}
	}

	public void invokeExecutionInits(Execution exec, Execution parent)
	throws UiException {
		if (_execInits != null) {
			for (Iterator it = _execInits.iterator(); it.hasNext();) {
				try {
					((ExecutionInit)it.next()).init(exec, parent);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the creation of a session
				}
			}
		}
	}
	public void invokeExecutionCleanups(Execution exec, Execution parent, List errs) {
		if (_execCleans != null) {
			for (Iterator it = _execCleans.iterator(); it.hasNext();) {
				final ExecutionCleanup listener = (ExecutionCleanup)it.next();
				try {
					listener.cleanup(exec, parent, errs);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
					if (errs != null) errs.add(ex);
				}
			}
		}
	}

	public void afterComponentAttached(Component comp, Page page) {
		if (_uiCycles != null) {
			for (Iterator it = _uiCycles.iterator(); it.hasNext();) {
				final UiLifeCycle listener = (UiLifeCycle)it.next();
				try {
					listener.afterComponentAttached(comp, page);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
				}
			}
		}
	}
	public void afterComponentDetached(Component comp, Page prevpage) {
		if (_uiCycles != null) {
			for (Iterator it = _uiCycles.iterator(); it.hasNext();) {
				final UiLifeCycle listener = (UiLifeCycle)it.next();
				try {
					listener.afterComponentDetached(comp, prevpage);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
				}
			}
		}
	}
	public void afterComponentMoved(Component parent, Component child, Component prevparent) {
		if (_uiCycles != null) {
			for (Iterator it = _uiCycles.iterator(); it.hasNext();) {
				final UiLifeCycle listener = (UiLifeCycle)it.next();
				try {
					listener.afterComponentMoved(parent, child, prevparent);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
				}
			}
		}
	}
	private void afterPageAttached(Page page, Desktop desktop) {
		if (_uiCycles != null) {
			for (Iterator it = _uiCycles.iterator(); it.hasNext();) {
				final UiLifeCycle listener = (UiLifeCycle)it.next();
				try {
					listener.afterPageAttached(page, desktop);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
				}
			}
		}
	}
	private void afterPageDetached(Page page, Desktop prevdesktop) {
		if (_uiCycles != null) {
			for (Iterator it = _uiCycles.iterator(); it.hasNext();) {
				final UiLifeCycle listener = (UiLifeCycle)it.next();
				try {
					listener.afterPageDetached(page, prevdesktop);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+listener, ex);
				}
			}
		}
	}

	//Server Push//
	public boolean enableServerPush(boolean enable) {
		final boolean old = _spush != null;
		if (old != enable) {
			if (enable) {
				final Class cls = getDevice().getServerPushClass();
				if (cls == null)
					throw new UiException("No server push defined. Make sure you are using the professional or enterprise edition, or you configured your own implementation");

				try {
					_spush = (ServerPush)cls.newInstance();
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex, "Unable to instantiate "+cls);
				}
				_spush.start(this);
			} else if (_spush.isActive()) {
				_spushShallStop = true;
			} else {
				_spush.stop();
				_spush = null;
			}
				
		}
		return old;
	}
	public boolean enableServerPush(ServerPush serverpush) {
		if (serverpush == null)
			return enableServerPush(false);

		final boolean old = _spush != null;
		if (!old || serverpush != _spush) {
			if (old) enableServerPush(false);

			_spush = serverpush;
			_spush.start(this);
		}
		return old;
	}
	public boolean isServerPushEnabled() {
		return _spush != null;
	}
	public ServerPush getServerPush() {
		return _spush;
	}
	public boolean activateServerPush(long timeout)
	throws InterruptedException {
		if (_spush == null)
			if (isAlive())
				throw new IllegalStateException("Before activation, the server push must be enabled for "+this);
			else
				throw new DesktopUnavailableException("Stopped");

		if (Events.inEventListener())
			throw new IllegalStateException("No need to invoke Executions.activate() in an event listener");

		return _spush.activate(timeout);
	}
	public void deactivateServerPush() {
		if (_spush != null)
			if (_spush.deactivate(_spushShallStop)) {
				_spushShallStop = false;
				_spush = null;
			}
	}
	public void setServerPushDelay(int min, int max, int factor) {
		if (_spush == null)
			throw new IllegalStateException("Not started");
		_spush.setDelay(min, max, factor);
	}

	public void onPiggybackListened(Component comp, boolean listen) {
		//we don't cache comp to avoid the risk of memory leak (maybe not
		//a problem)
		//On the other hand, most pages don't listen onPiggyback at all,
		//so _piggybackListened is good enough to improve the performance
		if (listen) _piggybackListened = true;
	}
	public void onPiggyback() {
		if (_piggybackListened) {
			for (Iterator it = _pages.values().iterator(); it.hasNext();) {
				final Page p = (Page)it.next();
				if (Executions.getCurrent().isAsyncUpdate(p)) { //ignore new created pages
					for (Iterator e = p.getRoots().iterator(); e.hasNext();) {
						final Component c = (Component)e.next();
						if (Events.isListened(c, Events.ON_PIGGYBACK, false)) //asap+deferrable
							Events.postEvent(new Event(Events.ON_PIGGYBACK, c));
					}
				}
			}
		}

		if (_spush != null)
			_spush.onPiggyback();
	}

	//AU Response//
	public void responseSent(String channel, String reqId, Object response) {
		if (reqId != null)
			_lastRes.put(channel, new Object[] {reqId, response});
	}
	public Object getLastResponse(String channel, String reqId) {
		final Object[] info = (Object[])_lastRes.get(channel);
		return info != null && Objects.equals(reqId, info[0]) ? info[1]: null;
	}
	public int getResponseId(boolean advance) {
		if (advance && ++_resId > MAX_RESPONSE_ID)
			_resId = 1;
		return _resId;
	}
	public void setResponseId(int resId) {
		if (resId > MAX_RESPONSE_ID)
			throw new IllegalArgumentException("Invalid response ID: "+resId);
		_resId = resId < 0 ? 0: resId;
	}

	public void invalidate() {
		for (Iterator it = _pages.values().iterator(); it.hasNext();) {
			final Page page = (Page)it.next();
			if (((PageCtrl)page).getOwner() == null)
				page.invalidate();
		}
	}
}
