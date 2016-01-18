package com.gifisan.mtp.component;

import java.io.IOException;

import com.gifisan.mtp.common.CloseUtil;
import com.gifisan.mtp.schedule.ServletAcceptJob;
import com.gifisan.mtp.server.Request;
import com.gifisan.mtp.server.Response;
import com.gifisan.mtp.server.ServerEndPoint;

public class ServletAcceptJobImpl implements ServletAcceptJob {

	private Request		request	= null;
	private Response		response	= null;
	private ServletService	service	= null;
	private ServerEndPoint	endPoint	= null;

	public ServletAcceptJobImpl(ServerEndPoint endPoint,ServletService service,Request request,Response response) {
		this.endPoint = endPoint;
		this.service = service;
		this.request = request;
		this.response = response;
	}
	
	public void acceptException(IOException exception) {
		try {
			// error connection , should not flush
			response.flush();
		} catch (IOException e) {
			// ignore
			e.printStackTrace();
			// just close it
			CloseUtil.close(endPoint);
		}
	}

	public void schedule() {
		try {
			this.accept(request, response);
		} catch (MTPChannelException e) {
			e.printStackTrace();
		} catch (IOException exception) {
			exception.printStackTrace();
			// error connector
			this.acceptException(exception);
		} finally {
			if (endPoint.isEndConnect()) {
				CloseUtil.close(endPoint);
			}
		}
	}

	public void accept(Request request, Response response) throws IOException {
		service.accept(request, response);
	}

	public ServletAcceptJob update(ServerEndPoint endPoint, Request request, Response response) {
		this.endPoint = endPoint;
		this.request = request;
		this.response = response;
		return this;
	}

}