package com.gifisan.nio.server;

import com.gifisan.nio.component.Session;
import com.gifisan.nio.concurrent.ReentrantMap;

public class SessionFactory {

	private ReentrantMap<String, Session>	sessions	= new ReentrantMap<String, Session>();

	protected void putSession(Session session) {

		sessions.put(session.getSessionID(), session);
	}

	public Session getSession(String sessionID) {

		return sessions.get(sessionID);
	}

	protected void removeSession(Session session) {

		sessions.remove(session.getSessionID());
	}

}