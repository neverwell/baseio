/*
 * Copyright 2015-2017 GenerallyCloud.com
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.generallycloud.nio.component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import com.generallycloud.nio.ClosedChannelException;
import com.generallycloud.nio.common.Logger;
import com.generallycloud.nio.common.LoggerFactory;
import com.generallycloud.nio.common.ReleaseUtil;
import com.generallycloud.nio.component.concurrent.ExecutorEventLoop;
import com.generallycloud.nio.component.concurrent.ListQueue;
import com.generallycloud.nio.component.concurrent.ListQueueLinkUnsafe;
import com.generallycloud.nio.connector.AbstractChannelConnector;
import com.generallycloud.nio.protocol.ChannelReadFuture;
import com.generallycloud.nio.protocol.ChannelWriteFuture;
import com.generallycloud.nio.protocol.ProtocolDecoder;
import com.generallycloud.nio.protocol.ProtocolEncoder;
import com.generallycloud.nio.protocol.ProtocolFactory;
import com.generallycloud.nio.protocol.SslReadFuture;

public class NioSocketChannel extends AbstractChannel implements com.generallycloud.nio.component.SocketChannel {

	private SocketChannel				channel;
	private UnsafeSocketSession			session;
	private ChannelReadFuture			readFuture;
	private SslReadFuture				sslReadFuture;
	private SelectionKey				selectionKey;
	private boolean					networkWeak;
	private ProtocolDecoder				protocolDecoder;
	private ProtocolEncoder				protocolEncoder;
	private ProtocolFactory				protocolFactory;
	private SocketChannelContext			context;
	private ChannelWriteFuture			write_future;
	private long						next_network_weak	= Long.MAX_VALUE;
	private int						writeFutureLength	= 0;
	private ExecutorEventLoop			executorEventLoop;

	// FIXME 这里最好不要用ABQ，使用链式可增可减
	private ListQueue<ChannelWriteFuture>	write_futures		= new ListQueueLinkUnsafe<ChannelWriteFuture>();

	private static final Logger			logger			= LoggerFactory.getLogger(NioSocketChannel.class);

	// FIXME 改进network wake 机制
	// FIXME network weak check
	public NioSocketChannel(SocketSelectorEventLoop selectorLoop, SelectionKey selectionKey){
		super(selectorLoop);
		this.selectorEventLoop = selectorLoop;
		this.byteBufAllocator = selectorEventLoop.getByteBufAllocator();
		this.context = selectorLoop.getChannelContext();
		this.selectionKey = selectionKey;
		this.executorEventLoop = selectorLoop.getExecutorEventLoop();
		this.channel = (SocketChannel) selectionKey.channel();
		this.local = getLocalSocketAddress();
		this.protocolFactory = selectorLoop.getProtocolFactory();
		this.protocolDecoder = selectorLoop.getProtocolDecoder();
		this.protocolEncoder = selectorLoop.getProtocolEncoder();
		this.session = context.getSessionFactory().newUnsafeSession(this);
	}

	@Override
	public SocketChannelContext getContext() {
		return context;
	}
	
	@Override
	public boolean isComplete() {
		ReentrantLock lock = getChannelLock();
		lock.lock();
		try{
			return write_future == null && write_futures.size() == 0;
		}finally{
			lock.unlock();
		}
	}

	@Override
	public void fireEvent(SelectorEventLoop selectorLoop) throws IOException {

		if (!isOpened()) {
			throw new ClosedChannelException("closed");
		}
		
		if (write_future == null) {
			
			write_future = write_futures.poll();
			
			if (write_future == null) {
				return;
			}
		}

		if (!write_future.write(this)) {
			return;
		}
		
		writeFutureLength -= write_future.getBinaryLength();

		write_future.onSuccess(session);
		
		write_future = null;
	}
	
	@Override
	public int getWriteFutureLength() {
		return writeFutureLength;
	}

	@Override
	public InetSocketAddress getLocalSocketAddress() {
		if (local == null) {
			try {
				local = (InetSocketAddress) channel.getLocalAddress();
			} catch (IOException e) {
				local = ERROR_SOCKET_ADDRESS;
			}
		}
		return local;
	}

	@Override
	protected String getMarkPrefix() {
		return "Tcp";
	}

	@Override
	public ProtocolDecoder getProtocolDecoder() {
		return protocolDecoder;
	}

	@Override
	public ProtocolEncoder getProtocolEncoder() {
		return protocolEncoder;
	}

	@Override
	public ProtocolFactory getProtocolFactory() {
		return protocolFactory;
	}

	@Override
	public ChannelReadFuture getReadFuture() {
		return readFuture;
	}

	@Override
	public InetSocketAddress getRemoteSocketAddress() {
		if (remote == null) {
			try {
				remote = (InetSocketAddress) channel.getRemoteAddress();
			} catch (IOException e) {
				remote = ERROR_SOCKET_ADDRESS;
			}
		}
		return remote;
	}

	@Override
	public UnsafeSocketSession getSession() {
		return session;
	}

	@Override
	public int getWriteFutureSize() {
		return write_futures.size();
	}

	@Override
	public boolean isBlocking() {
		return channel.isBlocking();
	}

	@Override
	public boolean isNetworkWeak() {
		return networkWeak;
	}

	// FIXME 是否使用channel.isOpen()
	@Override
	public boolean isOpened() {
		return opened;
	}
	
	@Override
	public <T> T getOption(SocketOption<T> name) throws IOException {
		return channel.getOption(name);
	}

	@Override
	public <T> SocketChannel setOption(SocketOption<T> name,T value) throws IOException {
		return channel.setOption(name, value);
	}
	
	@Override
	public void flush(ChannelWriteFuture future) {
		
		UnsafeSocketSession session = getSession();
		
		if (!isOpened()) {
			future.onException(session, new ClosedChannelException(session.toString()));
			return;
		}
		
		ReentrantLock lock = getChannelLock();

		lock.lock();

		try {
			
			if (!write_futures.offer(future)) {
				future.onException(session, new RejectedExecutionException());
				return;
			}

			this.writeFutureLength += future.getBinaryLength();

			if (writeFutureLength > 1024 * 1024 * 10) {
				// FIXME 该连接写入过多啦
			}

			if (write_future == null && write_futures.size() > 1) {
				return;
			}

			selectorEventLoop.dispatch(this);

		} finally {

			lock.unlock();
		}
	}

	private void releaseWriteFutures() {

		ClosedChannelException e = null;

		if (write_future != null) {

			e = new ClosedChannelException(session.toString());

			ReleaseUtil.release(write_future);

			write_future.onException(session, e);
		}

		ListQueue<ChannelWriteFuture> writeFutures = this.write_futures;

		if (writeFutures.size() == 0) {
			return;
		}

		ChannelWriteFuture f = writeFutures.poll();

		UnsafeSocketSession session = this.session;

		if (e == null) {
			e = new ClosedChannelException(session.toString());
		}

		for (; f != null;) {

			f.onException(session, e);

			ReleaseUtil.release(f);

			f = writeFutures.poll();
		}
	}

	// FIXME 这里有问题
	@Override
	public void physicalClose() {

		getSession().physicalClose();

		// 最后一轮 //FIXME once
		try {
			this.fireEvent(selectorEventLoop);
		} catch (IOException e) {
		}

		this.opened = false;

		this.closing = false;

		ReleaseUtil.release(readFuture);
		ReleaseUtil.release(sslReadFuture);

		this.releaseWriteFutures();

		this.selectionKey.attach(null);

		try {
			this.channel.close();
		} catch (Exception e) {
		}

		this.selectionKey.cancel();

		ChannelService service = context.getChannelService();

		if (!(service instanceof AbstractChannelConnector)) {
			return;
		}

		try {
			((AbstractChannelConnector) service).physicalClose();
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}
	}

	@Override
	public int read(ByteBuffer buffer) throws IOException {
		return this.channel.read(buffer);
	}

	@Override
	public void setProtocolDecoder(ProtocolDecoder protocolDecoder) {
		this.protocolDecoder = protocolDecoder;
	}

	@Override
	public void setProtocolEncoder(ProtocolEncoder protocolEncoder) {
		this.protocolEncoder = protocolEncoder;
	}

	@Override
	public void setProtocolFactory(ProtocolFactory protocolFactory) {
		this.protocolFactory = protocolFactory;
	}

	@Override
	public void setReadFuture(ChannelReadFuture readFuture) {
		this.readFuture = readFuture;
	}

	@Override
	public void upNetworkState() {

		if (next_network_weak != Long.MAX_VALUE) {

			next_network_weak = Long.MAX_VALUE;

			networkWeak = false;
		}
	}

	@Override
	public void downNetworkState() {

		long current = System.currentTimeMillis();

		if (next_network_weak < Long.MAX_VALUE) {

			if (networkWeak) {
				return;
			}

			if (current > next_network_weak) {

				networkWeak = true;

				fireEvent(this);
			}

		} else {

			next_network_weak = current + 64;
		}

	}

	@Override
	public int write(ByteBuffer buffer) throws IOException {
		return channel.write(buffer);
	}

	@Override
	public SslReadFuture getSslReadFuture() {
		return sslReadFuture;
	}

	@Override
	public void setSslReadFuture(SslReadFuture future) {
		this.sslReadFuture = future;
	}

	@Override
	public boolean needFlush() {
		return write_futures.size() > 0;
	}

	@Override
	public boolean isPositive() {
		return !isNetworkWeak();
	}

	@Override
	public ExecutorEventLoop getExecutorEventLoop() {
		return executorEventLoop;
	}

	@Override
	public boolean isReadable() {
		return selectionKey.isReadable();
	}

}
